/**
 * 
 */
package org.goko.controller.grbl.v11.actions;

import org.goko.controller.grbl.v11.GrblControllerService;
import org.goko.controller.grbl.v11.bean.GrblMachineState;
import org.goko.core.common.exception.GkException;
import org.goko.core.controller.action.DefaultControllerAction;

/**
 * @author Psyko
 * @date 7 oct. 2017
 */
public class GrblSpindleOffAction extends AbstractGrblControllerAction {
	
	/**
	 * Constructor
	 * @param controllerService the Grbl service
	 */
	public GrblSpindleOffAction(GrblControllerService controllerService) {
		super(controllerService);
	}
	
	/** (inheritDoc)
	 * @see org.goko.core.controller.action.IGkControllerAction#canExecute()
	 */
	@Override
	public boolean canExecute() throws GkException {		
		return !GrblMachineState.UNDEFINED.equals(getControllerService().getState()) 
				&& !GrblMachineState.ALARM.equals(getControllerService().getState())
				&& !GrblMachineState.RUN.equals(getControllerService().getState());
	}
	
	/** (inheritDoc)
	 * @see org.goko.core.controller.action.IGkControllerAction#execute(java.lang.String[])
	 */
	@Override
	public void execute(Object... parameters) throws GkException {
		getControllerService().turnSpindleOff();
	}
	
	/** (inheritDoc)
	 * @see org.goko.core.controller.action.IGkControllerAction#getId()
	 */
	@Override
	public String getId() {
		return DefaultControllerAction.SPINDLE_OFF;
	}
}
