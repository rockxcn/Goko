/*
 *
 *   Goko
 *   Copyright (C) 2013, 2016  PsyKo
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package org.goko.controller.grbl.v09;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.goko.controller.grbl.v09.bean.GrblExecutionError;
import org.goko.controller.grbl.v09.bean.IGrblStateChangeListener;
import org.goko.controller.grbl.v09.bean.StatusReport;
import org.goko.controller.grbl.v09.configuration.GrblConfiguration;
import org.goko.controller.grbl.v09.configuration.GrblSetting;
import org.goko.controller.grbl.v09.configuration.IGrblConfigurationListener;
import org.goko.controller.grbl.v09.configuration.topic.GrblExecutionErrorTopic;
import org.goko.controller.grbl.v09.probe.ProbeCallable;
import org.goko.core.common.GkUtils;
import org.goko.core.common.applicative.logging.IApplicativeLogService;
import org.goko.core.common.event.EventBrokerUtils;
import org.goko.core.common.event.EventDispatcher;
import org.goko.core.common.event.EventListener;
import org.goko.core.common.event.ObservableDelegate;
import org.goko.core.common.exception.GkException;
import org.goko.core.common.exception.GkFunctionalException;
import org.goko.core.common.exception.GkTechnicalException;
import org.goko.core.common.measure.quantity.Length;
import org.goko.core.common.measure.quantity.Speed;
import org.goko.core.common.measure.units.Unit;
import org.goko.core.config.GokoPreference;
import org.goko.core.connection.IConnectionService;
import org.goko.core.controller.action.IGkControllerAction;
import org.goko.core.controller.bean.EnumControllerAxis;
import org.goko.core.controller.bean.MachineValue;
import org.goko.core.controller.bean.MachineValueDefinition;
import org.goko.core.controller.bean.ProbeRequest;
import org.goko.core.controller.bean.ProbeResult;
import org.goko.core.controller.event.IGCodeContextListener;
import org.goko.core.controller.event.MachineValueUpdateEvent;
import org.goko.core.gcode.element.GCodeLine;
import org.goko.core.gcode.element.ICoordinateSystem;
import org.goko.core.gcode.element.IGCodeProvider;
import org.goko.core.gcode.execution.ExecutionQueueType;
import org.goko.core.gcode.execution.ExecutionState;
import org.goko.core.gcode.execution.ExecutionToken;
import org.goko.core.gcode.execution.ExecutionTokenState;
import org.goko.core.gcode.rs274ngcv3.IRS274NGCService;
import org.goko.core.gcode.rs274ngcv3.context.CoordinateSystem;
import org.goko.core.gcode.rs274ngcv3.context.CoordinateSystemFactory;
import org.goko.core.gcode.rs274ngcv3.context.EnumDistanceMode;
import org.goko.core.gcode.rs274ngcv3.context.GCodeContext;
import org.goko.core.gcode.rs274ngcv3.context.GCodeContextObservable;
import org.goko.core.gcode.rs274ngcv3.element.InstructionProvider;
import org.goko.core.gcode.rs274ngcv3.instruction.SetDistanceModeInstruction;
import org.goko.core.gcode.rs274ngcv3.instruction.SetFeedRateInstruction;
import org.goko.core.gcode.rs274ngcv3.instruction.StraightFeedInstruction;
import org.goko.core.gcode.rs274ngcv3.instruction.StraightProbeInstruction;
import org.goko.core.gcode.service.IExecutionService;
import org.goko.core.log.GkLog;
import org.goko.core.math.Tuple6b;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;

/**
 * GRBL v0.8 Controller implementation
 *
 * @author PsyKo
 *
 */
public class GrblControllerService extends EventDispatcher implements IGrblControllerService {
	/**  Service ID */
	public static final String SERVICE_ID = "Grbl v0.9 Controller";
	/** Log */
	private static final GkLog LOG = GkLog.getLogger(GrblControllerService.class);
	/** GCode service*/
	private IRS274NGCService gcodeService;
	/** Status polling */
	private Timer statusPollingTimer;
	/** Controller action factory*/
	private GrblActionFactory grblActionFactory;
	/** Grbl configuration */
	private GrblConfiguration configuration;
	/** The configuration listeners */
	private List<IGrblConfigurationListener> configurationListener;
	/** Applicative log service */
	private IApplicativeLogService applicativeLogService;
	/** Grbl state object */
	private GrblState grblState;
	/** Grbl communicator */
	private GrblCommunicator communicator;
	/** The monitor service */
	private IExecutionService<ExecutionTokenState, ExecutionToken<ExecutionTokenState>> executionService;
	/** Event admin object to send topic to UI*/
	private EventAdmin eventAdmin;
	/** The Grbl Executor */
	private GrblExecutor grblExecutor;
	/** The history of used buffer for the last sent command s*/
	private LinkedBlockingQueue<Integer> usedBufferStack;
	/** GCode context listener delegate */
	private ObservableDelegate<IGCodeContextListener<GCodeContext>> gcodeContextListener;
	/** State listener delegate */
	private ObservableDelegate<IGrblStateChangeListener> stateListener;
	/** Completion service for probing */
	private CompletionService<ProbeResult> completionService;
	/** The probe callable for probe result handling*/
	private List<ProbeCallable> lstProbeCallable;
	/** The probe generated GCode */
	private IGCodeProvider probeGCodeProvider;
	/** Jog runnable */
	private GrblJogging grblJogging;
	
	/**
	 * Constructor
	 * @throws GkException GkException 
	 */
	public GrblControllerService() throws GkException {
		communicator	= new GrblCommunicator(this);
		usedBufferStack = new LinkedBlockingQueue<Integer>();
		grblExecutor	= new GrblExecutor(this, gcodeService);
		gcodeContextListener = new GCodeContextObservable();		
		stateListener = new ObservableDelegate<IGrblStateChangeListener>(IGrblStateChangeListener.class);
		configurationListener = new CopyOnWriteArrayList<IGrblConfigurationListener>();
	}

	/** (inheritDoc)
	 * @see org.goko.core.common.service.IGokoService#getServiceId()
	 */
	@Override
	public String getServiceId() throws GkException {
		return SERVICE_ID;
	}

	/** (inheritDoc)
	 * @see org.goko.core.common.service.IGokoService#start()
	 */
	@Override
	public void start() throws GkException {
		LOG.info("Starting " + SERVICE_ID);
		grblActionFactory 	 = new GrblActionFactory(this);
		configuration 		 = new GrblConfiguration();
		grblState 			 = new GrblState();
		grblState.addListener(this);
		
		grblJogging = new GrblJogging(this, communicator);		
		LOG.info("Successfully started " + SERVICE_ID);
	}

	/** (inheritDoc)
	 * @see org.goko.core.common.event.IObservable#addObserver(java.lang.Object)
	 */
	@Override
	public void addObserver(IGCodeContextListener<GCodeContext> observer) {
		gcodeContextListener.addObserver(observer);
	}
	
	/** (inheritDoc)
	 * @see org.goko.core.common.event.IObservable#removeObserver(java.lang.Object)
	 */
	@Override
	public boolean removeObserver(IGCodeContextListener<GCodeContext> observer) {		
		return gcodeContextListener.removeObserver(observer);
	}
	
	protected void stopStatusPolling(){
		statusPollingTimer.cancel();
	}

	public void startStatusPolling() {
		statusPollingTimer = new Timer();
		TimerTask task = new TimerTask(){
			@Override
			public void run() {
				try {
					refreshStatus();
				} catch (GkException e) {
					LOG.error(e);
				}
			}

		};
		statusPollingTimer.scheduleAtFixedRate(task, new Date(), 100);
	}


	/**
	 * @param evt
	 */
	@EventListener(MachineValueUpdateEvent.class)
	public void onMachineValueUpdate(MachineValueUpdateEvent evt){
		notifyListeners(evt);
	}

	/** (inheritDoc)
	 * @see org.goko.core.common.service.IGokoService#stop()
	 */
	@Override
	public void stop() throws GkException {		
		//persistValues();
	}

	/** (inheritDoc)
	 * @see org.goko.controller.grbl.v09.IGrblControllerService#send(org.goko.core.gcode.element.GCodeLine)
	 */
	@Override
	public void send(GCodeLine gCodeLine) throws GkException{
		String cmd = gcodeService.render(gCodeLine);
		List<Byte> byteCommand = GkUtils.toBytesList(cmd);
		int usedBufferCount = CollectionUtils.size(byteCommand);
		// Increment before we even send, to make sure we have enough space
		incrementUsedBufferCount(usedBufferCount + 2); // Dirty hack for end of line chars
		communicator.send( byteCommand );
		
	}

	/**
	 * Register a quantity of space buffer being used for the last sent data
	 * @param amount the amount of used space
	 * @throws GkException GkException
	 */
	private void incrementUsedBufferCount(int amount) throws GkException{
		usedBufferStack.add(amount);
		setUsedGrblBuffer(getUsedGrblBuffer() + amount);		
	}

	/**
	 * Decrement the used serial buffer by depiling the size of the send data, in reverse order
	 * @throws GkException GkException
	 */
	private void decrementUsedBufferCount() throws GkException{
		if(CollectionUtils.isNotEmpty(usedBufferStack)){
			Integer amount = usedBufferStack.poll();
			setUsedGrblBuffer(getUsedGrblBuffer() - amount);			
		}
	}
	/** (inheritDoc)
	 * @see org.goko.core.controller.IControllerService#getPosition()
	 */
	@Override
	public Tuple6b getPosition() throws GkException {
		return grblState.getWorkPosition();
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IControllerService#getPosition(org.goko.core.gcode.element.ICoordinateSystem)
	 */
	@Override
	public Tuple6b getPosition(ICoordinateSystem coordinateSystem) throws GkException {
		Tuple6b pos = getAbsolutePosition();
		pos.subtract(getCoordinateSystemOffset(coordinateSystem));
		return pos;	
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IControllerService#getAbsolutePosition()
	 */
	@Override
	public Tuple6b getAbsolutePosition() throws GkException {
		//return grblState.getWorkPosition();
		throw new GkTechnicalException("TO DO"); // FIXME
	}
	
	/** (inheritDoc)
	 * @see org.goko.core.controller.IThreeAxisControllerAdapter#getX()
	 */
	@Override
	public Length getX() throws GkException {
		Length xPos = grblState.getWorkPosition().getX();
		return xPos;
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IThreeAxisControllerAdapter#getY()
	 */
	@Override
	public Length getY() throws GkException {
		Length yPos = grblState.getWorkPosition().getY();
		return yPos;
	}

	/** (inheritDoc)
	 * @throws GkException
	 * @see org.goko.core.controller.IThreeAxisControllerAdapter#getZ()
	 */
	@Override
	public Length getZ() throws GkException {
		Length zPos = grblState.getWorkPosition().getZ();
		return zPos;
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IControllerService#isReadyForFileStreaming()
	 */
	@Override
	public boolean isReadyForFileStreaming() throws GkException {
		return GrblMachineState.READY.equals(getState()) || GrblMachineState.CHECK.equals(getState());
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IControllerService#getControllerAction(java.lang.String)
	 */
	@Override
	public IGkControllerAction getControllerAction(String actionId) throws GkException {
		IGkControllerAction action = grblActionFactory.findAction(actionId);
		if(action == null){
			throw new GkFunctionalException("Action '"+actionId+"' is not supported by this controller ("+getServiceId()+")");
		}
		return action;
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IControllerService#isControllerAction(java.lang.String)
	 */
	@Override
	public boolean isControllerAction(String actionId) throws GkException {
		return grblActionFactory.findAction(actionId) != null;
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IControllerService#getMachineValue(java.lang.String, java.lang.Class)
	 */
	@Override
	public <T> MachineValue<T> getMachineValue(String name, Class<T> clazz) throws GkException {
		return getGrblState().getValue(name, clazz);
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IControllerService#getMachineValueType(java.lang.String)
	 */
	@Override
	public Class<?> getMachineValueType(String name) throws GkException {
		return getGrblState().getControllerValueType(name);
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IControllerService#getMachineValueDefinition()
	 */
	@Override
	public List<MachineValueDefinition> getMachineValueDefinition() throws GkException {
		return getGrblState().getMachineValueDefinition();
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IControllerService#findMachineValueDefinition(java.lang.String)
	 */
	@Override
	public MachineValueDefinition findMachineValueDefinition(String id) throws GkException {
		return getGrblState().findMachineValueDefinition(id);
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IControllerService#getMachineValueDefinition(java.lang.String)
	 */
	@Override
	public MachineValueDefinition getMachineValueDefinition(String id) throws GkException {
		return getGrblState().getMachineValueDefinition(id);
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IControllerService#cancelFileSending()
	 */
	@Override
	public void cancelFileSending() throws GkException {
		stopMotion();
	}

	/**
	 * @param connectionService the connectionService to set
	 * @throws GkException GkException
	 */
	public void setConnectionService(IConnectionService connectionService) throws GkException {
		this.communicator.setConnectionService(connectionService);
	}

	/**
	 * Refresh the status of the remote Grbl controller
	 * @throws GkException GkException
	 */
	public void refreshStatus() throws GkException{
		if(isActivePollingEnabled()){
			communicator.sendWithoutEndLineCharacter( GkUtils.toBytesList(Grbl.CURRENT_STATUS) );
		}
	}

	public void refreshSpaceCoordinates() throws GkException{
		communicator.send( GkUtils.toBytesList(Grbl.VIEW_PARAMETERS) );
	}

	public void refreshParserState() throws GkException{
		communicator.send( GkUtils.toBytesList(Grbl.PARSER_STATE) );
	}

	public void refreshConfiguration() throws GkException{
		communicator.send( GkUtils.toBytesList(Grbl.CONFIGURATION) );
	}

	protected void handleConfigurationReading(String configurationMessage) throws GkException{
		String identifier = StringUtils.substringBefore(configurationMessage, "=").trim();
		String value 	  = StringUtils.substringBetween(configurationMessage, "=","(").trim();
		configuration.setValue(identifier, value);
		notifyConfigurationChanged(identifier);
		LOG.info("Updating setting '"+identifier+"' with value '"+value+"'");
	}

	protected void receiveParserState(String parserState) throws GkException {
		String[] commands = StringUtils.split(parserState," ");
		GCodeContext context = getGCodeContext();
		if(commands != null){
			for (String strCommand : commands) {
				IGCodeProvider provider = gcodeService.parse(strCommand);
				InstructionProvider instructions = gcodeService.getInstructions(context, provider);
				context = gcodeService.update(context, instructions);
			}
		}
		grblState.setCurrentContext(context);
		gcodeContextListener.getEventDispatcher().onGCodeContextEvent(context);
	}



	protected void handleError(String errorMessage) throws GkException{
		decrementUsedBufferCount();
		
		String formattedErrorMessage = formatErrorMessage(errorMessage);
		if(executionService.getExecutionState() == ExecutionState.RUNNING ||
				executionService.getExecutionState() == ExecutionState.PAUSED ||
				executionService.getExecutionState() == ExecutionState.ERROR ){		
			GCodeLine line = grblExecutor.markNextLineAsError();
			logError(formattedErrorMessage, line);
		}else{
			logError(formattedErrorMessage, null);
		}
		System.err.println("Error while state = "+grblExecutor.getState());
	}

	/**
	 * Replace the GCode error ID with the corresponding message if possible
	 * @param errorMessage the base message
	 * @return the formatted message
	 */
	private String formatErrorMessage(String errorMessage) {
		String formattedErrorMessage = errorMessage;
		if(errorMessage.matches("error: Invalid gcode ID:[0-9]*")){
			String[] tokens = errorMessage.split(":");
			String strId = tokens[2];
			Integer id = Integer.valueOf(strId);
			EnumGrblGCodeError error = EnumGrblGCodeError.findById(id);
			if(error != null){
				formattedErrorMessage = "error #"+id+" : "+error.getMessage();
			}
		}
		return formattedErrorMessage;
	}
	
	/**
	 * Log the given error on the given gcode line (line can be null)
	 * @param errorMessage the error message
	 * @param line the line (optionnal)
	 * @throws GkException GkException
	 */
	protected void logError(String errorMessage, GCodeLine line) throws GkException{
		String formattedErrorMessage = StringUtils.EMPTY;

		if(line != null){
			String lineStr = gcodeService.render(line);
			formattedErrorMessage = "Error with command '"+lineStr+"' : "+ StringUtils.substringAfter(errorMessage, "error: ");
		}else{
			formattedErrorMessage = "Grbl "+ errorMessage;
		}

		LOG.error(formattedErrorMessage);
		getApplicativeLogService().error(formattedErrorMessage, SERVICE_ID);

		// If not in check mode, let's pause the execution (disabled in check mode because check mode can't handle paused state and buffer would be flooded with commands)
		if(executionService.getExecutionState() == ExecutionState.RUNNING && !ObjectUtils.equals(GrblMachineState.CHECK, getState())){
			pauseMotion();
			EventBrokerUtils.send(eventAdmin, new GrblExecutionErrorTopic(), new GrblExecutionError("Error reported durring execution", "Execution was paused after Grbl reported an error. You can resume, or stop the execution at your own risk.", formattedErrorMessage));
		}else{
			EventBrokerUtils.send(eventAdmin, new GrblExecutionErrorTopic(), new GrblExecutionError("Grbl error", "Grbl reported an error.", formattedErrorMessage));
		}
	}

	protected void handleOkResponse() throws GkException{
		decrementUsedBufferCount();		
		grblState.setPlannerBuffer( grblState.getPlannerBuffer() + 1);
		if(executionService.getExecutionState() == ExecutionState.RUNNING ||
			executionService.getExecutionState() == ExecutionState.PAUSED ||
			executionService.getExecutionState() == ExecutionState.ERROR ){
			grblExecutor.confirmNextLineExecution();
		}
	}

	protected void initialiseConnectedState() throws GkException{
		setUsedGrblBuffer(0);
		refreshConfiguration();
		refreshSpaceCoordinates();
		refreshParserState();
	}

	protected void handleStatusReport(StatusReport statusReport) throws GkException{
		GrblMachineState previousState = getState();
		setState(statusReport.getState());
		grblState.setMachinePosition(statusReport.getMachinePosition(), getConfiguration().getReportUnit());
		grblState.setWorkPosition(statusReport.getWorkPosition(), getConfiguration().getReportUnit());
		grblState.setPlannerBuffer(statusReport.getPlannerBuffer());
				
		if(!ObjectUtils.equals(previousState, statusReport.getState())){
			eventAdmin.sendEvent(new Event(CONTROLLER_TOPIC_STATE_UPDATE, (Map<String, ?>)null));
		}
		gcodeContextListener.getEventDispatcher().onGCodeContextEvent(getGCodeContext());
	}

	protected void handleProbeResult(ProbeResult probeResult){
		if(CollectionUtils.isNotEmpty(lstProbeCallable)){			
			ProbeCallable callable = lstProbeCallable.remove(0);
			callable.setProbeResult(probeResult);
		}
	}
	
	@Override
	public GrblMachineState getState() throws GkException{
		return grblState.getState();
	}

	public void setState(GrblMachineState state) throws GkException{
		grblState.setState(state);
		eventAdmin.sendEvent(new Event(CONTROLLER_TOPIC_STATE_UPDATE, (Map<String, ?>)null));
		stateListener.getEventDispatcher().execute();
	}

	protected GrblMachineState getGrblStateFromString(String code){
		switch(code){
			case "Alarm": return GrblMachineState.ALARM;
			case "Idle" : return GrblMachineState.READY;
			case "Queue" : return GrblMachineState.MOTION_HOLDING;
			case "Run" : return GrblMachineState.MOTION_RUNNING;
			case "Home" : return GrblMachineState.HOMING;
			case "Check" : return GrblMachineState.CHECK;
			case "Hold" : return GrblMachineState.HOLD;
			default: return GrblMachineState.UNDEFINED;
		}
	}

	/*
	 *  Action related methods
	 */

	public void startHomingSequence() throws GkException{
		List<Byte> homeCommand = new ArrayList<Byte>();
		homeCommand.addAll(GkUtils.toBytesList(Grbl.HOME_COMMAND));
		communicator.send( homeCommand );
	}

	/**
	 * Pause the motion by sending a pause character to Grbl
	 * If the execution queue is not empty, it is also paused
	 * @throws GkException GkException
	 */
	public void pauseMotion() throws GkException{
		List<Byte> pauseCommand = new ArrayList<Byte>();
		pauseCommand.add(Grbl.PAUSE_COMMAND);
		communicator.sendImmediately( pauseCommand );
		if(executionService.getExecutionState() != ExecutionState.IDLE){
			executionService.pauseQueueExecution();
		}
	}

	/**
	 * Stop the motion by sending a pause and a flush character to Grbl
	 * If the execution queue is not empty, it is also stopped and emptied
	 * @throws GkException GkException
	 */
	public void stopMotion() throws GkException{
		List<Byte> stopCommand = new ArrayList<Byte>();
		stopCommand.add(Grbl.PAUSE_COMMAND);
		stopCommand.add(Grbl.RESET_COMMAND);
		
		communicator.sendImmediately(stopCommand);
		if(executionService.getExecutionState() != ExecutionState.IDLE){
			executionService.stopQueueExecution();
		}
		setUsedGrblBuffer(0);
		usedBufferStack.clear();
		
		// FIXME : test to perform a soft reset when state get to HOLD. Doesn't work since hold doesn't mean that the machine has stopped 
		// addStateListener(new GrblResetOnHoldListener(this, communicator));
	}

	/**
	 * Start the motion by sending a resume character to Grbl
	 * If the execution queue is paused, it is also resumed
	 * @throws GkException GkException
	 */
	public void startMotion() throws GkException{
		List<Byte> startResumeCommand = new ArrayList<Byte>();
		startResumeCommand.add(Grbl.RESUME_COMMAND);
		communicator.sendWithoutEndLineCharacter( startResumeCommand );
		if(executionService.getExecutionState() == ExecutionState.PAUSED){
			executionService.resumeQueueExecution();
		}else{
			executionService.beginQueueExecution(ExecutionQueueType.DEFAULT);
		}
	}
	
	public void resumeMotion() throws GkException{
		List<Byte> startResumeCommand = new ArrayList<Byte>();
		startResumeCommand.add(Grbl.RESUME_COMMAND);
		communicator.sendWithoutEndLineCharacter( startResumeCommand );		
		executionService.resumeQueueExecution();		
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IStepJogService#stopJog()
	 */
	@Override
	public void stopJog() throws GkException {
		grblJogging.stopJog();
	}

	public void resetZero(List<String> axes) throws GkException{
		List<Byte> lstBytes = GkUtils.toBytesList("G92");
		if(CollectionUtils.isNotEmpty(axes)){
			for (String axe : axes) {
				lstBytes.addAll(GkUtils.toBytesList(axe+"0"));
			}
		}else{
			lstBytes.addAll( GkUtils.toBytesList("X0Y0Z0"));
		}
		communicator.send(lstBytes);
	}

	public void killAlarm() throws GkException{
		List<Byte> lstBytes = GkUtils.toBytesList("$X");
		communicator.send(lstBytes);
	}

	/**
	 * @return the usedGrblBuffer
	 * @throws GkException  GkException
	 */
	@Override
	public int getUsedGrblBuffer() throws GkException {
		return grblState.getUsedGrblBuffer();
	}

	/**
	 * @param usedGrblBuffer the usedGrblBuffer to set
	 * @throws GkException GkException
	 */
	public void setUsedGrblBuffer(int usedGrblBuffer) throws GkException {
		grblState.setUsedGrblBuffer(usedGrblBuffer);
	}

	/** (inheritDoc)
	 * @see org.goko.controller.grbl.v09.IGrblControllerService#getUsedGrblPlannerBuffer()
	 */
	@Override
	public int getUsedGrblPlannerBuffer() throws GkException {		
		return grblState.getPlannerBuffer();
	}
	
	/**
	 * @return the configuration
	 */
	@Override
	public GrblConfiguration getConfiguration() {
		return configuration;
	}

	/**
	 * @param configuration the configuration to set
	 * @throws GkException GkException
	 */
	@Override
	public void setConfiguration(GrblConfiguration configuration) throws GkException {
		
		if(CollectionUtils.isNotEmpty( configuration.getLstGrblSetting() )){
			List<GrblSetting<?>> lstSetting = configuration.getLstGrblSetting();
			List<Byte> cfgCommand = new ArrayList<Byte>();
			
			for (GrblSetting<?> newGrblSetting : lstSetting) {
				cfgCommand.addAll(GkUtils.toBytesList(newGrblSetting.getIdentifier()+"="+newGrblSetting.getValueAsString() ));
				communicator.send( cfgCommand );
				cfgCommand.clear();
				notifyConfigurationChanged(newGrblSetting.getIdentifier());
				// Start of dirty hack to avoid flooding Grbl RX buffer. Need to work on a proper solution
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					LOG.error(e);
				}
				// End of dirty hack to avoid flooding Grbl RX buffer. Need to work on a proper solution
			}			
		}
		this.configuration = configuration;
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IControllerService#moveToAbsolutePosition(org.goko.core.math.Tuple6b)
	 */
	@Override
	public void moveToAbsolutePosition(Tuple6b position) throws GkException {

	}

	/**
	 * @return the gcodeService
	 */
	public IRS274NGCService getGCodeService() {
		return gcodeService;
	}

	/**
	 * @param gcodeService the gcodeService to set
	 */
	public void setGCodeService(IRS274NGCService gcodeService) {
		this.gcodeService = gcodeService;
	}

	/**
	 * @return the applicativeLogService
	 */
	public IApplicativeLogService getApplicativeLogService() {
		return applicativeLogService;
	}

	/**
	 * @param applicativeLogService the applicativeLogService to set
	 */
	public void setApplicativeLogService(IApplicativeLogService applicativeLogService) {
		this.applicativeLogService = applicativeLogService;
	}


	/** (inheritDoc)
	 * @see org.goko.controller.grbl.v09.IGrblControllerService#getGrblState()
	 */
	@Override
	public GrblState getGrblState() {
		return grblState;
	}

	protected void setCoordinateSystemOffset(CoordinateSystem coordinateSystem, Tuple6b value) throws GkException{
		getGrblState().setOffset(coordinateSystem, value);
		gcodeContextListener.getEventDispatcher().onGCodeContextEvent(getGCodeContext());
	}
	/** (inheritDoc)
	 * @see org.goko.core.controller.IControllerService#getGCodeContext()
	 */
	@Override
	public GCodeContext getGCodeContext() throws GkException {
		return grblState.getCurrentContext();
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.ICoordinateSystemAdapter#getCoordinateSystemOffset(org.goko.core.gcode.element.ICoordinateSystem)
	 */
	@Override
	public Tuple6b getCoordinateSystemOffset(ICoordinateSystem cs) throws GkException {
		return grblState.getOffset(cs);
	}
	/** (inheritDoc)
	 * @see org.goko.core.controller.ICoordinateSystemAdapter#getCoordinateSystem()
	 */
	@Override
	public List<ICoordinateSystem> getCoordinateSystem() throws GkException {
		List<ICoordinateSystem> lstCoordinateSystem = new ArrayList<>();
		lstCoordinateSystem.addAll(new CoordinateSystemFactory().get());
		return lstCoordinateSystem;
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.ICoordinateSystemAdapter#getCurrentCoordinateSystem()
	 */
	@Override
	public ICoordinateSystem getCurrentCoordinateSystem() throws GkException {
		return grblState.getCurrentContext().getCoordinateSystem();
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.ICoordinateSystemAdapter#setCurrentCoordinateSystem(org.goko.core.gcode.element.ICoordinateSystem)
	 */
	@Override
	public void setCurrentCoordinateSystem(ICoordinateSystem cs) throws GkException {
		communicator.send( GkUtils.toBytesList( cs.getCode()) );
		communicator.send( GkUtils.toBytesList( "$G" ) );
	}
	/** (inheritDoc)
	 * @see org.goko.core.controller.ICoordinateSystemAdapter#resetCurrentCoordinateSystem()
	 */
	@Override
	public void resetCurrentCoordinateSystem() throws GkException {
		ICoordinateSystem cs = getGrblState().getCurrentContext().getCoordinateSystem();
		String cmd = "G10";
		switch (cs.getCode()) {
		case "G54": cmd +="P1";
		break;
		case "G55": cmd +="P2";
		break;
		case "G56": cmd +="P3";
		break;
		case "G57": cmd +="P4";
		break;
		case "G58": cmd +="P5";
		break;
		case "G59": cmd +="P6";
		break;
		default: throw new GkFunctionalException("GRBL-002", cs.getCode());
		}
		Tuple6b offsets = getCoordinateSystemOffset(getCurrentCoordinateSystem());
		Tuple6b mPos = new Tuple6b(getPosition());
		mPos = mPos.add(offsets);
		cmd += "L2";
		cmd += "X"+getPositionAsString(mPos.getX());
		cmd += "Y"+getPositionAsString(mPos.getY());
		cmd += "Z"+getPositionAsString(mPos.getZ());
		communicator.send( GkUtils.toBytesList( cmd ) );
		communicator.send( GkUtils.toBytesList( Grbl.VIEW_PARAMETERS ) );
	}
	

	/** (inheritDoc)
	 * @see org.goko.core.controller.ICoordinateSystemAdapter#updateCoordinateSystemPosition(org.goko.core.gcode.element.ICoordinateSystem, org.goko.core.math.Tuple6b)
	 */
	@Override
	public void updateCoordinateSystemPosition(ICoordinateSystem cs, Tuple6b position) throws GkException {
		String cmd = "G10";
		switch (cs.getCode()) {
		case "G54": cmd +="P1";
		break;
		case "G55": cmd +="P2";
		break;
		case "G56": cmd +="P3";
		break;
		case "G57": cmd +="P4";
		break;
		case "G58": cmd +="P5";
		break;
		case "G59": cmd +="P6";
		break;
		default: throw new GkFunctionalException("GRBL-002", cs.getCode());
		}
		Tuple6b mPos = new Tuple6b(position);
		cmd += "L2";
		cmd += "X"+getPositionAsString(mPos.getX());
		cmd += "Y"+getPositionAsString(mPos.getY());
		cmd += "Z"+getPositionAsString(mPos.getZ());
		communicator.send(GkUtils.toBytesList(cmd));
		communicator.send(GkUtils.toBytesList(Grbl.VIEW_PARAMETERS));
	}
	
	/**
	 * Returns the given Length quantity as a String, formatted using the goko preferences for decimal numbers
	 * @param q the quantity to format
	 * @return a String
	 * @throws GkException GkException
	 */
	protected String getPositionAsString(Length q) throws GkException{
		return GokoPreference.getInstance().format( q.to(getCurrentUnit()), true, false);
	}

	/**
	 * Returns the current unit in the Grbl Conteext. It can be different from the unit in the goko preferences
	 * @return Unit
	 */
	private Unit<Length> getCurrentUnit() {
		return grblState.getContextUnit().getUnit();
	}

	/** (inheritDoc)
	 * @see org.goko.controller.grbl.v09.IGrblControllerService#setActivePollingEnabled(boolean)
	 */
	@Override
	public void setActivePollingEnabled(boolean enabled) throws GkException {
		grblState.setActivePolling(enabled);
	}

	/** (inheritDoc)
	 * @see org.goko.controller.grbl.v09.IGrblControllerService#isActivePollingEnabled()
	 */
	@Override
	public boolean isActivePollingEnabled() throws GkException {
		return grblState.isActivePolling();
	}

	/** (inheritDoc)
	 * @see org.goko.controller.grbl.v09.IGrblControllerService#setCheckModeEnabled(boolean)
	 */
	@Override
	public void setCheckModeEnabled(boolean enabled) throws GkException {
		if((enabled && ObjectUtils.equals(GrblMachineState.READY, getState())) || // Check mode is disabled and we want to enable it
			(!enabled && ObjectUtils.equals(GrblMachineState.CHECK, getState())) ){ // Check mode is enabled and we want to disable it
			communicator.send(GkUtils.toBytesList(Grbl.CHECK_MODE));
		}else{
			throw new GkFunctionalException("GRBL-001", String.valueOf(enabled), getState().getLabel());
		}
	}

	/**
	 * @return the eventAdmin
	 */
	public EventAdmin getEventAdmin() {
		return eventAdmin;
	}
	/**
	 * @param eventAdmin the eventAdmin to set
	 */
	public void setEventAdmin(EventAdmin eventAdmin) {
		this.eventAdmin = eventAdmin;
	}

	/**
	 * @return the monitorService
	 */
	public IExecutionService<ExecutionTokenState, ExecutionToken<ExecutionTokenState>> getMonitorService() {
		return executionService;
	}
	/**
	 * @param monitorService the monitorService to set
	 * @throws GkException GkException
	 */
	public void setMonitorService(IExecutionService<ExecutionTokenState, ExecutionToken<ExecutionTokenState>> monitorService) throws GkException {
		this.executionService = monitorService;
		this.grblExecutor = new GrblExecutor(this, gcodeService);
		this.executionService.setExecutor(grblExecutor);//new GrblDebugExecutor(gcodeService));
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IControllerConfigurationFileExporter#getFileExtension()
	 */
	@Override
	public String getFileExtension() {
		return "grbl.cfg";
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IControllerConfigurationFileExporter#canExport()
	 */
	@Override
	public boolean canExport() throws GkException {
		return GrblMachineState.READY.equals(getState());
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IControllerConfigurationFileExporter#exportTo(java.io.OutputStream)
	 */
	@Override
	public void exportTo(OutputStream stream) throws GkException {
		GrblConfiguration config = getConfiguration();
		StringBuffer buffer = new StringBuffer();
		for (GrblSetting<?> setting : config.getLstGrblSetting()) {
			buffer.append(setting.getIdentifier()+"="+setting.getValueAsString());
			buffer.append(System.lineSeparator());
		}
		try {
			stream.write(buffer.toString().getBytes());
		} catch (IOException e) {
			throw new GkTechnicalException(e);
		}
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IControllerConfigurationFileImporter#canImport()
	 */
	@Override
	public boolean canImport() throws GkException {
		return GrblMachineState.READY.equals(getState());
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IControllerConfigurationFileImporter#importFrom(java.io.InputStream)
	 */
	@Override
	public void importFrom(InputStream inputStream) throws GkException {
		GrblConfiguration cfg = getConfiguration();
		Scanner scanner = new Scanner(inputStream);
		while(scanner.hasNextLine()){
			String line = scanner.nextLine();
			String[] tokens = line.split("=");
			if(tokens != null && tokens.length == 2){
				cfg.setValue(tokens[0], tokens[1]);
			}else{
				LOG.warn("Ignoring configuration line ["+line+"] because it's malformatted.");
			}
		}
		scanner.close();
		setConfiguration(cfg);
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IJogService#jog(org.goko.core.controller.bean.EnumControllerAxis, org.goko.core.common.measure.quantity.Length, org.goko.core.common.measure.quantity.Speed)
	 */
	@Override
	public void jog(EnumControllerAxis axis, Length step, Speed feedrate) throws GkException {
		grblJogging.jog(axis, step, feedrate);
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IControllerService#verifyReadyForExecution()
	 */
	@Override
	public void verifyReadyForExecution() throws GkException {
		if(!isReadyForFileStreaming()){
			throw new GkFunctionalException("GRBL-003");
		}
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IProbingService#probe(java.util.List)
	 */
	@Override
	public CompletionService<ProbeResult> probe(List<ProbeRequest> lstProbeRequest) throws GkException {		
		Executor executor = Executors.newSingleThreadExecutor();
		this.completionService = new ExecutorCompletionService<ProbeResult>(executor);		
		this.lstProbeCallable = new ArrayList<>();
		
		for (ProbeRequest probeRequest : lstProbeRequest) {
			ProbeCallable probeCallable = new ProbeCallable();
			this.lstProbeCallable.add(probeCallable);
			completionService.submit(probeCallable);			
		}
		
		probeGCodeProvider = getZProbingCode(lstProbeRequest, getGCodeContext());
		probeGCodeProvider.setCode("Grbl probing");
		gcodeService.addGCodeProvider(probeGCodeProvider);
		probeGCodeProvider = gcodeService.getGCodeProvider(probeGCodeProvider.getId());// Required since internally the provider is a new one
		executionService.clearExecutionQueue(ExecutionQueueType.SYSTEM);
		executionService.addToExecutionQueue(ExecutionQueueType.SYSTEM, probeGCodeProvider);		
		executionService.beginQueueExecution(ExecutionQueueType.SYSTEM); 
		
		return completionService;
	}
	
	private IGCodeProvider getZProbingCode(List<ProbeRequest> lstProbeRequest, GCodeContext gcodeContext) throws GkException{		
		InstructionProvider instrProvider = new InstructionProvider();
		// Force distance mode to absolute
		instrProvider.addInstruction( new SetDistanceModeInstruction(EnumDistanceMode.ABSOLUTE) );
		
		for (ProbeRequest probeRequest : lstProbeRequest) {
			// Move to clearance coordinate 
			instrProvider.addInstruction( new SetFeedRateInstruction(probeRequest.getMotionFeedrate()) );
			instrProvider.addInstruction( new StraightFeedInstruction(null, null, probeRequest.getClearance(), null, null, null) );
			// Move to probe position		
			instrProvider.addInstruction( new StraightFeedInstruction(probeRequest.getProbeCoordinate().getX(), probeRequest.getProbeCoordinate().getY(), null, null, null, null) );
			// Move to probe start position
			instrProvider.addInstruction( new StraightFeedInstruction(null, null, probeRequest.getProbeStart(), null, null, null) );
			// Actual probe command
			instrProvider.addInstruction( new SetFeedRateInstruction(probeRequest.getProbeFeedrate()) );
			instrProvider.addInstruction( new StraightProbeInstruction(null, null, probeRequest.getProbeEnd(), null, null, null) );
			// Move to clearance coordinate 
			instrProvider.addInstruction( new SetFeedRateInstruction(probeRequest.getMotionFeedrate()) );
			instrProvider.addInstruction( new StraightFeedInstruction(null, null, probeRequest.getClearance(), null, null, null) );
		}		
		return gcodeService.getGCodeProvider(gcodeContext, instrProvider);
	}
	
	/** (inheritDoc)
	 * @see org.goko.core.controller.IProbingService#probe(org.goko.core.controller.bean.EnumControllerAxis, double, double)
	 */
	@Override
	public CompletionService<ProbeResult> probe(ProbeRequest probeRequest) throws GkException {
		List<ProbeRequest> lstProbeRequest = new ArrayList<ProbeRequest>();
		lstProbeRequest.add(probeRequest);
		return probe(lstProbeRequest);		
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IProbingService#checkReadyToProbe()
	 */
	@Override
	public void checkReadyToProbe() throws GkException {
		if(!isReadyToProbe()){
			throw new GkFunctionalException("GRBL-003");
		}
	}

	/** (inheritDoc)
	 * @see org.goko.core.controller.IProbingService#isReadyToProbe()
	 */
	@Override
	public boolean isReadyToProbe() {
		try {
			return GrblMachineState.READY.equals(getState()) || GrblMachineState.CHECK.equals(getState());
		} catch (GkException e) {
			LOG.error(e);
		}
		return false;
	}
	
	void addStateListener(IGrblStateChangeListener listener){
		stateListener.addObserver(listener);
	}

	void removeStateListener(IGrblStateChangeListener listener){
		stateListener.removeObserver(listener);
	}
	
	/** (inheritDoc)
	 * @see org.goko.controller.grbl.v09.IGrblControllerService#addConfigurationListener(org.goko.controller.grbl.v09.configuration.IGrblConfigurationListener)
	 */
	@Override
	public void addConfigurationListener(IGrblConfigurationListener listener) {
		if(!configurationListener.contains(listener)){
			configurationListener.add(listener);
		}
	}
	
	/** (inheritDoc)
	 * @see org.goko.controller.grbl.v09.IGrblControllerService#removeConfigurationListener(org.goko.controller.grbl.v09.configuration.IGrblConfigurationListener)
	 */
	@Override
	public void removeConfigurationListener(IGrblConfigurationListener listener) {
		configurationListener.remove(listener);
	}
	
	/**
	 * Notifies the registered listeners for a configuration change
	 * @param the identifier of the setting that changed
	 * @throws GkException GkException 
	 */
	private void notifyConfigurationChanged(String identifier) throws GkException{
		for (IGrblConfigurationListener listener : configurationListener) {
			listener.onConfigurationChanged(configuration, identifier); // Use a copy of the configuration
		}
	}
	
	/** (inheritDoc)
	 * @see org.goko.controller.grbl.v09.IGrblControllerService#updateConfiguration(org.goko.controller.grbl.v09.configuration.GrblConfiguration)
	 */
	@Override
	public void updateConfiguration(GrblConfiguration configuration) throws GkException {
		setConfiguration(configuration);
	}
		
	/** (inheritDoc)
	 * @see org.goko.controller.grbl.v09.IGrblControllerService#resetGrbl()
	 */
	@Override
	public void resetGrbl() throws GkException {
		List<Byte> resetCommand = new ArrayList<Byte>();
		resetCommand.add(Grbl.RESET_COMMAND);
		communicator.sendImmediately(resetCommand);
	}
}
