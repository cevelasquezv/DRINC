/* 
 * Based on the Original PRoPHET code updated to PRoPHETv2 router 
 * by Samo Grasic(samo@grasic.net) - Jun 2011
 */
package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import java.util.Random;

import routing.util.RoutingInfo;


import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import util.Tuple;

/**
 * Implementation of DTNforwarding router as described in
 * ref.paper
 * Faltan tiempos y next_hop (implementar listas???? en vez de mapas?)
 */
public class DrincRouter2 extends ActiveRouter {
	/** delivery predictability initialization constant*/
	public static final double PEncMax = 0.9;
	/** typical interconnection time in seconds*/ //2 hours - should be @param
	public static final double I_TYP = 7200;
	/** delivery predictability aging constant */
	public static final double GAMMA = 0.999885791;
	Random randomGenerator = new Random();
	/** identifier for the initial number of copies setting ({@value})*/ 
	public static final String NROF_COPIES = "nrofCopies";

	/** Prophet router's setting namespace ({@value})*/ 
	public static final String DRINC_NS = "DRINC";
	/** Message property key */
	public static final String MSG_COUNT_PROPERTY = DRINC_NS + "." +
		"copies";
	
	protected int initialNrofCopies;
	
	/**
	 * Number of seconds in time unit -setting id ({@value}).
	 * How many seconds one time unit is when calculating aging of 
	 * delivery predictions. Should be tweaked for the scenario.*/
	public static final String SECONDS_IN_UNIT_S ="secondsInTimeUnit";
	
	/** the value of nrof seconds in time unit -setting */
	private int secondsInTimeUnit;

	/** delivery predictabilities */
	private Map<DTNHost, Double> preds;

	/** neighboring nodes' availabilities */
	private Map<DTNHost, Double> availabilities;
	
	/** last encouter timestamp (sim)time */
	private Map<DTNHost, Double> lastEncouterTime;
	
	/** last delivery predictability update (sim)time */
	private double lastAgeUpdate;
	
	/**
	 * Constructor. Creates a new message router based on the settings in
	 * the given Settings object.
	 * @param s The settings object
	 */
	public DrincRouter2(Settings s) {
		super(s);
		Settings drincSettings = new Settings(DRINC_NS);
		secondsInTimeUnit = drincSettings.getInt(SECONDS_IN_UNIT_S);

		initialNrofCopies = drincSettings.getInt(NROF_COPIES);
		
		initPreds();
		initEncTimes();
	}

	/**
	 * Copy constructor.
	 * @param The router prototype where setting values are copied from
	 */
	protected DrincRouter2(DrincRouter2 r) {
		super(r);
		this.secondsInTimeUnit = r.secondsInTimeUnit;
		this.initialNrofCopies = r.initialNrofCopies;
		initPreds();
		initEncTimes();
	}
	
	/**
	 * Initializes lastEncouterTime hash
	 */
	private void initEncTimes() {
		this.lastEncouterTime = new HashMap<DTNHost, Double>();
	}

	/**
	 * Initializes predictability hash
	 */
	private void initPreds() {
		this.availabilities = new HashMap<DTNHost, Double>();
		this.preds = new HashMap<DTNHost, Double>();
	}
	
	@Override
	public int receiveMessage(Message m, DTNHost from) {
		return super.receiveMessage(m, from);
	}
		
	@Override
	public void changedConnection(Connection con) {
		if (con.isUp()) {
			DTNHost otherHost = con.getOtherNode(getHost());
			updateDeliveryPredFor(otherHost);
			updateTransitivePreds(otherHost);
		}
	}
	
	@Override
	public Message messageTransferred(String id, DTNHost from) {
		Message msg = super.messageTransferred(id, from);
		Integer nrofCopies = (Integer)msg.getProperty(MSG_COUNT_PROPERTY);
		
		assert nrofCopies != null : "Not a Drinc message: " + msg;
		
		/* The receiving node gets only single copy */
		nrofCopies = 1;
		
		msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
		return msg;
	}
	
	@Override 
	public boolean createNewMessage(Message msg) {
		makeRoomForNewMessage(msg.getSize());

		msg.setTtl(this.msgTtl);
		msg.addProperty(MSG_COUNT_PROPERTY, new Integer(initialNrofCopies));
		addToMessages(msg, true);
		return true;
	}
	
		
	/**
	 * Updates delivery predictions for a host.
	 * <CODE>P(a,b) = P(a,b)_old + (1 - P(a,b)_old) * PEnc
	 * PEnc(intvl) =
     *        P_encounter_max * (intvl / I_typ) for 0<= intvl <= I_typ
     *        P_encounter_max^k for intvl > I_typ</CODE>
	 * @param host The host we just met
	 */
	private void updateDeliveryPredFor(DTNHost host) {
		double PEnc;
		double simTime = SimClock.getTime();
		double lastEncTime=getEncTimeFor(host);
		double oldValue = getPredFor(host);
		double newValue = PEncMax; //Initial Value

		if(lastEncTime==0)
		{
			newValue = PEncMax;
		}
		else
			if((simTime-lastEncTime)<I_TYP) //time < typical?
			{
				PEnc = PEncMax*((simTime-lastEncTime)/I_TYP);
				newValue = oldValue + (1 - oldValue) * PEnc;
			}
			else //too much time
			{
				newValue = Math.pow(oldValue,((simTime-lastEncTime)/I_TYP));
			}

		//double oldValue = getPredFor(host);
		//newValue = oldValue + (1 - oldValue) * PEnc;
		preds.put(host, newValue);
		availabilities.put(host, newValue); //it is the same when no transitivity
		lastEncouterTime.put(host, simTime);
	}
	
	/**
	 * Returns the timestamp of the last encounter of with the host or 0 if 
	 * entry for the host doesn't exist.
	 * @param host The host to look the timestamp for
	 * @return the last timestamp of encouter with the host
	 */
	public double getEncTimeFor(DTNHost host) {
		if (lastEncouterTime.containsKey(host)) {
			return lastEncouterTime.get(host);
		}
		else {
			return 0;
		}
	}
	
		/**
	 * Returns the current prediction (P) value for a host or 0 if entry for
	 * the host doesn't exist.
	 * @param host The host to look the P for
	 * @return the current P value
	 */
	public double getPredFor(DTNHost host) {
		ageDeliveryPreds(); // make sure preds are updated before getting
		if (preds.containsKey(host)) {
			return preds.get(host);
		}
		else {
			return 0;
		}
	}
	
	/**
	 * Updates transitive (A->B->C) delivery predictions.
	 * <CODE>P(a,c) = P(a,c)_old + (1 - P(a,c)_old) * P(a,b) * P(b,c) * BETA
	 *       P(a,c) = P(a,b)*P(b,c) 
	 * </CODE>
	 * @param host The B host who we just met
	 */
	private void updateTransitivePreds(DTNHost host) { //other predictions should be <DTNhost, pred>, but pred = <DTN_Int, pred>
		MessageRouter otherRouter = host.getRouter();
		assert otherRouter instanceof DrincRouter2 : 
			"DrincRouter2 only works with other routers of same type";
		
		double pForHost = getPredFor(host); // P(a,b)
		Map<DTNHost, Double> othersPreds = 
			((DrincRouter2)otherRouter).getDeliveryPreds();
		
		for (Map.Entry<DTNHost, Double> e : othersPreds.entrySet()) {
			if (e.getKey() == getHost()) {
				continue; // don't add yourself
			}

			double pOld = getPredFor(e.getKey()); // P(a,c)_old next hop?
			double pNew = pForHost * e.getValue();
			if(pNew>pOld)
				preds.put(e.getKey(), pNew); //update next_hop for e.getKey()?
		}
	}

	/**
	 * Ages all entries in the delivery predictions.
	 * <CODE>P(a,b) = P(a,b)_old * (GAMMA ^ k)</CODE>, where k is number of
	 * time units that have elapsed since the last time the metric was aged.
	 * P(a,b) = P(a,b)_old ^ k
	 * @see #SECONDS_IN_UNIT_S
	 */
	private void ageDeliveryPreds() {
		double timeDiff = (SimClock.getTime() - this.lastAgeUpdate) / secondsInTimeUnit;
		
		if (timeDiff == 0) {
			return;
		}
		
		//double mult = Math.pow(GAMMA, timeDiff);
		for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
			//e.setValue(e.getValue()*mult);
			e.setValue(Math.pow(e.getValue(), timeDiff));
		}
		
		this.lastAgeUpdate = SimClock.getTime();
	}
	
	/**
	 * Returns a map of this router's delivery predictions
	 * @return a map of this router's delivery predictions
	 */
	private Map<DTNHost, Double> getDeliveryPreds() {
		ageDeliveryPreds(); // make sure the aging is done
		return this.preds;
	}
	
	@Override
	public void update() {
		super.update();
		if (!canStartTransfer() ||isTransferring()) {
			return; // nothing to transfer or is currently transferring 
		}
		
		// try messages that could be delivered to final recipient
		if (exchangeDeliverableMessages() != null) {
			return;
		}
		
		/* create a list of DRINCMessages that have copies left to distribute */
		@SuppressWarnings(value = "unchecked")
		List<Message> copiesLeft = sortByQueueMode(getMessagesWithCopiesLeft());
		
		if (copiesLeft.size() > 0) {
			/* try to send those messages */
			this.tryMessagesToConnections(copiesLeft, getConnections());
		}
		
		tryOtherMessages();
	}
	
	/**
	 * Creates and returns a list of messages this router is currently
	 * carrying and still has copies left to distribute (nrof copies > 1).
	 * @return A list of messages that have copies left
	 */
	protected List<Message> getMessagesWithCopiesLeft() {
		List<Message> list = new ArrayList<Message>();

		for (Message m : getMessageCollection()) {
			Integer nrofCopies = (Integer)m.getProperty(MSG_COUNT_PROPERTY);
			assert nrofCopies != null : "DRINC message " + m + " didn't have " + 
				"nrof copies property!";
			if (nrofCopies > 1) {
				list.add(m);
			}
		}

		return list;
	}
	
	/**
	 * Called just before a transfer is finalized (by 
	 * {@link ActiveRouter#update()}).
	 * Reduces the number of copies we have left for a message. 
	 * nrof copies left is reduced by one. 
	 */
	@Override
	protected void transferDone(Connection con) {
		Integer nrofCopies;
		String msgId = con.getMessage().getId();
		/* get this router's copy of the message */
		Message msg = getMessage(msgId);

		if (msg == null) { // message has been dropped from the buffer after..
			return; // ..start of transfer -> no need to reduce amount of copies
		}
		
		/* reduce the amount of copies left */
		nrofCopies = (Integer)msg.getProperty(MSG_COUNT_PROPERTY);
		nrofCopies--;

		msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
	}
	
	/**
	 * Tries to send all other messages to all connected hosts ordered by
	 * their delivery probability
	 * @return The return value of {@link #tryMessagesForConnected(List)}
	 */
	private Tuple<Message, Connection> tryOtherMessages() {
		List<Tuple<Message, Connection>> messages = 
			new ArrayList<Tuple<Message, Connection>>(); 
	
		Collection<Message> msgCollection = getMessageCollection();
		
		/* for all connected hosts collect all messages that have a higher
		   probability of delivery by the other host */
		for (Connection con : getConnections()) {
			DTNHost other = con.getOtherNode(getHost());
			DrincRouter2 othRouter = (DrincRouter2)other.getRouter();
			
			if (othRouter.isTransferring()) {
				continue; // skip hosts that are transferring
			}
			
			for (Message m : msgCollection) {
				if (othRouter.hasMessage(m.getId())) {
					continue; // skip messages that the other one has
				}
				if((othRouter.getPredFor(m.getTo()) >= getPredFor(m.getTo())))
				{
					messages.add(new Tuple<Message, Connection>(m,con));
				}
			}			
		}
		
		if (messages.size() == 0) {
			return null;
		}
		
		// sort the message-connection tuples
		Collections.sort(messages, new TupleComparator());
		return tryMessagesForConnected(messages);	// try to send messages
	}
	
	/**
	 * Comparator for Message-Connection-Tuples that orders the tuples by
	 * their delivery probability by the host on the other side of the 
	 * connection (GRTRMax)
	 */
	private class TupleComparator implements Comparator 
		<Tuple<Message, Connection>> {

		public int compare(Tuple<Message, Connection> tuple1,
				Tuple<Message, Connection> tuple2) {
			// delivery probability of tuple1's message with tuple1's connection
			double p1 = ((DrincRouter2)tuple1.getValue().
					getOtherNode(getHost()).getRouter()).getPredFor(
					tuple1.getKey().getTo());
			// -"- tuple2...
			double p2 = ((DrincRouter2)tuple2.getValue().
					getOtherNode(getHost()).getRouter()).getPredFor(
					tuple2.getKey().getTo());

			// bigger probability should come first
			if (p2 == p1) {
				/* equal probabilities -> let queue mode decide */
				return 0;
				//return compareByQueueMode(tuple1.getKey(), tuple2.getKey());
			}
			else if (p2 < p1) {
				return -1;
			}
			else{
				return 1;
			}
		}
	}
	
	@Override
	public RoutingInfo getRoutingInfo() {
		ageDeliveryPreds();
		RoutingInfo top = super.getRoutingInfo();
		RoutingInfo ri = new RoutingInfo(preds.size() + 
				" delivery prediction(s)");
		
		for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
			DTNHost host = e.getKey();
			Double value = e.getValue();
			
			ri.addMoreInfo(new RoutingInfo(String.format("%s : %.6f", 
					host, value)));
		}
		
		top.addMoreInfo(ri);
		return top;
	}
	
	@Override
	public MessageRouter replicate() {
		DrincRouter2 r = new DrincRouter2(this);
		return r;
	}
}