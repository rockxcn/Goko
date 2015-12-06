package org.goko.core.gcode.rs274ngcv3.context;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.goko.core.common.exception.GkException;
import org.goko.core.common.measure.Units;
import org.goko.core.common.measure.quantity.Angle;
import org.goko.core.common.measure.quantity.Length;
import org.goko.core.common.measure.quantity.type.BigDecimalQuantity;
import org.goko.core.common.measure.quantity.type.NumberQuantity;
import org.goko.core.gcode.element.IGCodeContext;
import org.goko.core.math.Tuple6b;

public class GCodeContext implements IGCodeContext{
	/**
	 * Current motion mode 
	 */
	private EnumMotionMode motionMode;	
	/**
	 * Current distance mode 
	 */
	private EnumDistanceMode distanceMode;
	/**
	 * Current working plane
	 */
	private EnumPlane plane;
	/**
	 * Current unit 
	 */
	private EnumUnit unit;
	/**
	 * Current tool in use
	 */
	private Integer activeToolNumber;
	/**
	 * Current tool being selected (might be different from the <i>activeToolNumber</i>
	 */
	private Integer selectedToolNumber;
	/**
	 * Current coordinate system
	 */
	private EnumCoordinateSystem coordinateSystem;
	/**
	 * Current spindle mode 
	 */
	private EnumSpindleMode spindleMode;
	/**
	 * Current feed rate
	 */
	private BigDecimal feedrate;
	/**
	 * Current spindle speed
	 */
	private BigDecimal spindleSpeed;
	/**
	 * Offset from origin 
	 */
	private Tuple6b originOffset;
	/**
	 * Offset from origin active  
	 */
	private boolean originOffsetActive;
	/**
	 * Current motion control mode 
	 */
	private EnumMotionControl motionControl;
	/** 
	 * X coordinate in the current coordinate system 
	 */
	private BigDecimalQuantity<Length> x;
	/**
	 *  Y coordinate in the current coordinate system 
	 */
	private BigDecimalQuantity<Length> y;
	/**
	 *  Z coordinate in the current coordinate system 
	 */
	private BigDecimalQuantity<Length> z;
	/**
	 *  A coordinate in the current coordinate system
	 */
	private BigDecimalQuantity<Angle> a;
	/**
	 *  B coordinate in the current coordinate system 
	 */
	private BigDecimalQuantity<Angle> b;
	/**
	 *  C coordinate in the current coordinate system
	 */
	private BigDecimalQuantity<Angle> c;
	/**
	 * Coordinates system offsets
	 */
	private Map<EnumCoordinateSystem, Tuple6b> coordinateSystemData;
	
	// _______________________ END OF FIELDS DEFINITION _______________________
	
	/** Empty constructor */
	public GCodeContext() {
		this.originOffset = new Tuple6b().setZero();
		this.coordinateSystemData = new HashMap<EnumCoordinateSystem, Tuple6b>();
		this.originOffsetActive = true;
		this.motionMode = EnumMotionMode.RAPID;
		this.distanceMode = EnumDistanceMode.ABSOLUTE;
		this.plane = EnumPlane.XY_PLANE;
		this.unit = EnumUnit.MILLIMETERS;
		this.activeToolNumber = 0;
		this.selectedToolNumber = 0;
		this.coordinateSystem = EnumCoordinateSystem.G54;
		this.spindleMode = EnumSpindleMode.OFF;
		this.spindleSpeed = BigDecimal.ZERO;
		this.feedrate = BigDecimal.ZERO;		
		this.x = NumberQuantity.of(BigDecimal.ZERO, unit.getUnit());
		this.y = NumberQuantity.of(BigDecimal.ZERO, unit.getUnit());
		this.z = NumberQuantity.of(BigDecimal.ZERO, unit.getUnit());
		this.a = NumberQuantity.of(BigDecimal.ZERO, Units.DEGREE_ANGLE);
		this.b = NumberQuantity.of(BigDecimal.ZERO, Units.DEGREE_ANGLE);
		this.c = NumberQuantity.of(BigDecimal.ZERO, Units.DEGREE_ANGLE);
	}
	
	/**
	 * Copy constructor
	 * @param context the context to copy
	 */
	public GCodeContext(GCodeContext context) {
		this();
		this.motionMode = context.motionMode;
		this.distanceMode = context.distanceMode;
		this.plane = context.plane;
		this.unit = context.unit;
		this.activeToolNumber = context.activeToolNumber;
		this.selectedToolNumber = context.selectedToolNumber;
		this.coordinateSystem = context.coordinateSystem;
		this.spindleMode = context.spindleMode;
		this.spindleSpeed = context.spindleSpeed;
		this.feedrate = context.feedrate;
		this.originOffset = new Tuple6b(context.originOffset);
		this.originOffsetActive = context.originOffsetActive;
		this.x = context.x;
		this.y = context.y;
		this.z = context.z;
		this.a = context.a;
		this.b = context.b;
		this.c = context.c;
		
		// Copy coordinate systems data		
		if(!context.coordinateSystemData.isEmpty()){
			for (EnumCoordinateSystem enumCs : context.coordinateSystemData.keySet()) {
				coordinateSystemData.put(enumCs, new Tuple6b(context.getCoordinateSystemData(enumCs)));
			}
		}
	}
	
	// _______________________ END OF CONSTRUCTORS DEFINITION _______________________
	
	/**
	 * @return the motionMode
	 */
	public EnumMotionMode getMotionMode() {
		return motionMode;
	}
	/**
	 * @param motionMode the motionMode to set
	 */
	public void setMotionMode(EnumMotionMode motionMode) {
		this.motionMode = motionMode;
	}
	/**
	 * @return the distanceMode
	 */
	public EnumDistanceMode getDistanceMode() {
		return distanceMode;
	}
	/**
	 * @param distanceMode the distanceMode to set
	 */
	public void setDistanceMode(EnumDistanceMode distanceMode) {
		this.distanceMode = distanceMode;
	}
	/**
	 * @return the plane
	 */
	public EnumPlane getPlane() {
		return plane;
	}
	/**
	 * @param plane the plane to set
	 */
	public void setPlane(EnumPlane plane) {
		this.plane = plane;
	}
	/**
	 * @return the unit
	 */
	public EnumUnit getUnit() {
		return unit;
	}
	/**
	 * @param unit the unit to set
	 */
	public void setUnit(EnumUnit unit) {
		this.unit = unit;
	}
	/**
	 * @return the activeToolNumber
	 */
	public Integer getActiveToolNumber() {
		return activeToolNumber;
	}
	/**
	 * @param activeToolNumber the activeToolNumber to set
	 */
	public void setActiveToolNumber(Integer activeToolNumber) {
		this.activeToolNumber = activeToolNumber;
	}
	/**
	 * @return the selectedToolNumber
	 */
	public Integer getSelectedToolNumber() {
		return selectedToolNumber;
	}
	/**
	 * @param selectedToolNumber the selectedToolNumber to set
	 */
	public void setSelectedToolNumber(Integer selectedToolNumber) {
		this.selectedToolNumber = selectedToolNumber;
	}
	/**
	 * @return the coordinateSystem
	 */
	public EnumCoordinateSystem getCoordinateSystem() {
		return coordinateSystem;
	}
	/**
	 * @param coordinateSystem the coordinateSystem to set
	 */
	public void setCoordinateSystem(EnumCoordinateSystem coordinateSystem) {
		this.coordinateSystem = coordinateSystem;
	}
	/**
	 * @return the spindleMode
	 */
	public EnumSpindleMode getSpindleMode() {
		return spindleMode;
	}
	/**
	 * @param spindleMode the spindleMode to set
	 */
	public void setSpindleMode(EnumSpindleMode spindleMode) {
		this.spindleMode = spindleMode;
	}
	/**
	 * @return the feedrate
	 */
	public BigDecimal getFeedrate() {
		return feedrate;
	}
	/**
	 * @param feedrate the feedrate to set
	 */
	public void setFeedrate(BigDecimal feedrate) {
		this.feedrate = feedrate;
	}
	/**
	 * @return the spindleSpeed
	 */
	public BigDecimal getSpindleSpeed() {
		return spindleSpeed;
	}
	/**
	 * @param spindleSpeed the spindleSpeed to set
	 */
	public void setSpindleSpeed(BigDecimal spindleSpeed) {
		this.spindleSpeed = spindleSpeed;
	}
	
	/**
	 * Update the position
	 * @param x the x coordinate
	 * @param y the y coordinate
	 * @param z the z coordinate
	 * @param a the a coordinate
	 * @param b the b coordinate
	 * @param c the c coordinate
	 * @throws GkException GkException
	 */
	public void setPosition(BigDecimalQuantity<Length> x, BigDecimalQuantity<Length> y, BigDecimalQuantity<Length> z, BigDecimalQuantity<Angle> a, BigDecimalQuantity<Angle> b, BigDecimalQuantity<Angle> c) throws GkException{		
		if( x != null) setX(x);
		if( y != null) setY(y);
		if( z != null) setZ(z);
		if( a != null) setA(a);
		if( b != null) setB(b);
		if( c != null) setC(c);
	}
	
	/**
	 * Update the position
	 * @param tuple the tuple to get the position from 
	 * @throws GkException GkException
	 */
	public void setPosition(Tuple6b tuple) throws GkException{
		setPosition(tuple.getX(),tuple.getY(),tuple.getZ(),tuple.getA(),tuple.getB(),tuple.getC());
	}
	
	public Tuple6b getPosition() throws GkException{
		return new Tuple6b(getX(),getY(),getZ(),getA(),getB(),getC());
	}
	/**
	 * @return the x
	 */
	public BigDecimalQuantity<Length> getX() {
		return x;
	}
	/**
	 * @param x the x to set
	 */
	public void setX(BigDecimalQuantity<Length> x) {
		this.x = x;
	}
	/**
	 * @return the y
	 */
	public BigDecimalQuantity<Length> getY() {
		return y;
	}
	/**
	 * @param y the y to set
	 */
	public void setY(BigDecimalQuantity<Length> y) {
		this.y = y;
	}
	/**
	 * @return the z
	 */
	public BigDecimalQuantity<Length> getZ() {
		return z;
	}
	/**
	 * @param z the z to set
	 */
	public void setZ(BigDecimalQuantity<Length> z) {
		this.z = z;
	}
	/**
	 * @return the a
	 */
	public BigDecimalQuantity<Angle> getA() {
		return a;
	}
	/**
	 * @param a the a to set
	 */
	public void setA(BigDecimalQuantity<Angle> a) {
		this.a = a;
	}
	/**
	 * @return the b
	 */
	public BigDecimalQuantity<Angle> getB() {
		return b;
	}
	/**
	 * @param b the b to set
	 */
	public void setB(BigDecimalQuantity<Angle> b) {
		this.b = b;
	}
	/**
	 * @return the c
	 */
	public BigDecimalQuantity<Angle> getC() {
		return c;
	}
	/**
	 * @param c the c to set
	 */
	public void setC(BigDecimalQuantity<Angle> c) {
		this.c = c;
	}
	
	/**
	 * Returns the offset for the given coordinate system
	 * @param cs the coordinate system 
	 * @return {@link Tuple6b}
	 */
	public Tuple6b getCoordinateSystemData(EnumCoordinateSystem cs){
		if(!coordinateSystemData.containsKey(cs)){
			coordinateSystemData.put(cs, new Tuple6b(getUnit().getUnit(), Units.DEGREE_ANGLE).setZero());
		}
		return coordinateSystemData.get(cs);
	}
	
	/**
	 * Sets the offset for the given coordinate system
	 * @param cs the coordinate system 
	 * @param offset the offset to set
	 */
	public void setCoordinateSystemData(EnumCoordinateSystem cs, Tuple6b offset){		
		coordinateSystemData.put(cs, offset);	
		
	}

	/**
	 * @return the originOffset
	 */
	public Tuple6b getOriginOffset() {
		return originOffset;
	}

	/**
	 * @param originOffset the originOffset to set
	 */
	public void setOriginOffset(Tuple6b originOffset) {
		this.originOffset = originOffset;
	}

	/**
	 * @return the originOffsetActive
	 */
	public boolean isOriginOffsetActive() {
		return originOffsetActive;
	}

	/**
	 * @param originOffsetActive the originOffsetActive to set
	 */
	public void setOriginOffsetActive(boolean originOffsetActive) {
		this.originOffsetActive = originOffsetActive;
	}

	/**
	 * @return the motionControl
	 */
	public EnumMotionControl getMotionControl() {
		return motionControl;
	}

	/**
	 * @param motionControl the motionControl to set
	 */
	public void setMotionControl(EnumMotionControl motionControl) {
		this.motionControl = motionControl;
	}
	
}