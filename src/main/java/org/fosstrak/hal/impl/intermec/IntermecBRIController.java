/**
 * 
 */
package org.fosstrak.hal.impl.intermec;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Vector;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.fosstrak.hal.AsynchronousIdentifyListener;
import org.fosstrak.hal.HardwareException;
import org.fosstrak.hal.MemoryBankDescriptor;
import org.fosstrak.hal.MemoryDescriptor;
import org.fosstrak.hal.Observation;
import org.fosstrak.hal.OutOfBoundsException;
import org.fosstrak.hal.ReadPointNotFoundException;
import org.fosstrak.hal.TagDescriptor;
import org.fosstrak.hal.Trigger;
import org.fosstrak.hal.UnsignedByteArray;
import org.fosstrak.hal.UnsupportedOperationException;
import org.fosstrak.hal.impl.intermec.connector.TCPBRISocketConnector;
import org.fosstrak.hal.transponder.EPCTransponderModel;
import org.fosstrak.hal.transponder.IDType;
import org.fosstrak.hal.transponder.InventoryItem;
import org.fosstrak.hal.transponder.RFTechnology;
import org.fosstrak.hal.transponder.TransponderType;
import org.fosstrak.hal.util.ResourceLocator;

/**
 * @author "David Figueroa"
 * 
 */
public class IntermecBRIController implements IntermecController {

	static Logger log = Logger.getLogger(IntermecBRIController.class);

	/**
	 * The TCP/IP connector that understand BRI protocol
	 */
	private TCPBRISocketConnector connector;

	/**
	 * HAL name
	 */
	private String halName;

	/**
	 * BRI Network properties
	 */
	private String host;
	private int port;
	private int timeout;

	/**
	 * 
	 */
	private boolean isConnected = false;

	/**
	 * The configuration files
	 */
	private String defaultConfigFile = "/props/IntermecBRIController_default.xml";
	private String configFile;
	private String epcTransponderModelsConfig;

	/**
	 * The configuration
	 */
	private XMLConfiguration config = null;

	private HashMap<String, InventoryItem> currentInventory = new HashMap<String, InventoryItem>();

	/**
	 * Number of read points
	 */
	private int numberOfSources = 0;

	/**
	 * Logical read points
	 */
	private HashMap<String, String> logicalSources;
	private HashMap<String, String> antennaNames;

	/**
	 * 
	 * @param halName
	 * @param configFile
	 */
	public IntermecBRIController(String halName, String configFile) {
		
		this.halName = halName;
		this.configFile = configFile;
		
		try {
			log.info("Trying to initialize " + halName);
			this.initialize();
			
		} catch (Exception e) {
			log.error("Reader initialization failed", e);
		}
	}

	/**
	 * Initialize a reader.
	 * 
	 * @throws HardwareException
	 */
	public void initialize() throws HardwareException {

		// read parameters from configuration file
		this.config = new XMLConfiguration();
		config.setListDelimiter(',');
		URL fileurl = ResourceLocator.getURL(configFile, defaultConfigFile, this.getClass());

		try {

			config.load(fileurl);

			host = config.getString("host");
			port = config.getInt("port", 2189);
			timeout = config.getInt("timeout");

			epcTransponderModelsConfig = config.getString("epcTransponderModelsConfig");

			logicalSources = new HashMap<String, String>();
			antennaNames = new HashMap<String, String>();
			numberOfSources = config.getMaxIndex("logicalSource") + 1;

			if (numberOfSources > 4) {
				numberOfSources = 4;
			}

			for (int i = 0; i < numberOfSources; i++) {
				// key to current read point
				String key = "logicalSource(" + i + ")";

				// read point name
				String logicalSourceName = config.getString(key + ".name");

				log.debug("Property found: " + key + ".name = " + logicalSourceName);
				
				// The associated antennas to this sourceName
				String[] antennas = config.getStringArray(key + ".antennas");

				// the source name maps to the full string of antennas separated with "commas"
				logicalSources.put(logicalSourceName, StringUtils.join(antennas, ","));
				
				for (String antenna : antennas) {
					
					log.debug("Property found: " + key + ".antennas = " + antenna);

					// associating each antenna to the sourceName
					antennaNames.put(antenna, logicalSourceName);
				}
			}

		} catch (ConfigurationException e) {
			String message = "Error in reader property file";
			log.error("initialize: " + message, e);
			throw new HardwareException(message, e);
		}

		try {
			log.info("Trying to connect to: " + host + ":" + port + " ...");
			initReader();
			log.info("Reader initialized.");

		} catch (HardwareException e) {
			String message = "Error initializing reader";
			log.error("initialize: " + message, e);
			throw new HardwareException(message, e);
		}
	}

	/**
	 * Tries to connect to reader's proxy service
	 * 
	 * @throws HardwareException
	 */
	protected void initReader() throws HardwareException {
		try {

			connector = new TCPBRISocketConnector(host, port, timeout);

			isConnected = connector.isConnected();

		} catch (Exception e) {
			String message = "initReader: Network communication error";
			log.error("initReader: " + message, e);
			throw new HardwareException(message, e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#identify(java.lang.String[])
	 */
	public Observation[] identify(String[] readPointNames) throws ReadPointNotFoundException, HardwareException {

		currentInventory.clear();

		Observation[] observations = new Observation[readPointNames.length];

		for (int i = 0; i < readPointNames.length; i++) {

			observations[i] = new Observation();
			observations[i].setHalName(getHALName());
			observations[i].setReadPointName(readPointNames[i]);

			List<InventoryItem> inventory = getInventory(readPointNames[i]);
			List<String> ids = new Vector<String>();
			List<TagDescriptor> tds = new Vector<TagDescriptor>();

			for (InventoryItem item : inventory) {

				String id = item.id;

				if (item.transponderType == TransponderType.EPCclass1Gen2) {

					IDType idType = IDType.getIdType("EPC", config.getString("idTypesConfig"));

					EPCTransponderModel tagModel = item.epcTransponderModel;

					MemoryBankDescriptor[] memoryBankDescriptors = new MemoryBankDescriptor[4];
					memoryBankDescriptors[0] = new MemoryBankDescriptor(tagModel.getReservedSize(),
							tagModel.getReservedReadable(), tagModel.getReservedWriteable());
					memoryBankDescriptors[1] = new MemoryBankDescriptor(tagModel.getEpcSize(),
							tagModel.getEpcReadable(), tagModel.getEpcWriteable());
					memoryBankDescriptors[2] = new MemoryBankDescriptor(tagModel.getTidSize(),
							tagModel.getTidReadable(), tagModel.getTidWriteable());
					memoryBankDescriptors[3] = new MemoryBankDescriptor(tagModel.getUserSize(),
							tagModel.getUserReadable(), tagModel.getUserWriteable());

					MemoryDescriptor memoryDescriptor = new MemoryDescriptor(memoryBankDescriptors);

					TagDescriptor td = new TagDescriptor(idType, memoryDescriptor);

					tds.add(td);
				}

				item.readPoint = readPointNames[i];
				ids.add(id);
				currentInventory.put(id, item);
			}

			int len = ids.size();
			String[] ids_arr = new String[len];
			ids_arr = ids.toArray(ids_arr);

			if (tds.size() == len) {
				TagDescriptor[] tds_arr = new TagDescriptor[len];
				tds_arr = tds.toArray(tds_arr);
				observations[i].setTagDescriptors(tds_arr);
			}
			observations[i].setIds(ids_arr);
			observations[i].setTimestamp(System.currentTimeMillis());
		}

		return observations;
	}

	/**
	 * 
	 * @param sourceName
	 * @return
	 * @throws HardwareException
	 */
	synchronized protected List<InventoryItem> getInventory(String sourceName) throws HardwareException {

		List<InventoryItem> inventory = new Vector<InventoryItem>();

		// set transponder type to EPCclass1gen2
		byte trType = (byte) 0x84;

		if (!isConnected) {
			// Returning empty inventory
			return null;
		}

		try {

			// Getting the logical source associated antennas
			String antennas = logicalSources.get(sourceName);

			// Reading only for the configured antennas
			String command = "ATTRIB ANTS=" + antennas + ";R";

			String[] tagList = connector.sendReadRequest(command);

			if (tagList == null) {
				return inventory;
			}

			log.debug("Inventory size: " + tagList.length);

			for (String tagid : tagList) {

				InventoryItem item = new InventoryItem();

				item.transponderType = TransponderType.getType(trType);
				item.rfTechnology = RFTechnology.getType(trType);

				byte[] tid = { (byte) 0x000 };
				item.tid = tid;

				item.epcTransponderModel = EPCTransponderModel.getEpcTrasponderModel(item.tid,
						epcTransponderModelsConfig);

				item.id = tagid;
				item.readPoint = sourceName;

				inventory.add(item);
			}
			
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
			}

			notify();

			return inventory;

		} catch (Exception e) {

			// isConnected = false;

			throw new HardwareException("getInventory: " + e.getMessage(), e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.fosstrak.hal.HardwareAbstraction#startAsynchronousIdentify(java.lang
	 * .String[], org.fosstrak.hal.Trigger)
	 */
	public void startAsynchronousIdentify(String[] readPointNames, Trigger trigger) throws ReadPointNotFoundException,
			HardwareException, UnsupportedOperationException {

		throw new UnsupportedOperationException("startAsynchronousIdentify: Unsupported method");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#stopAsynchronousIdentify()
	 */
	public void stopAsynchronousIdentify() throws HardwareException, UnsupportedOperationException {

		throw new UnsupportedOperationException("stopAsynchronousIdentify: Unsupported method");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#isAsynchronousIdentifyRunning()
	 */
	public boolean isAsynchronousIdentifyRunning() throws HardwareException, UnsupportedOperationException {

		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.fosstrak.hal.HardwareAbstraction#addAsynchronousIdentifyListener(
	 * org.fosstrak.hal.AsynchronousIdentifyListener)
	 */
	public void addAsynchronousIdentifyListener(AsynchronousIdentifyListener listener) throws HardwareException,
			UnsupportedOperationException {

		throw new UnsupportedOperationException("addAsynchronousIdentifyListener: Unsupported method");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.fosstrak.hal.HardwareAbstraction#removeAsynchronousIdentifyListener
	 * (org.fosstrak.hal.AsynchronousIdentifyListener)
	 */
	public void removeAsynchronousIdentifyListener(AsynchronousIdentifyListener listener) throws HardwareException,
			UnsupportedOperationException {

		throw new UnsupportedOperationException("removeAsynchronousIdentifyListener: Unsupported method");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#supportsAsynchronousIdentify()
	 */
	public boolean supportsAsynchronousIdentify() {

		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#readBytes(java.lang.String,
	 * java.lang.String, int, int, int, java.lang.String[])
	 */
	public UnsignedByteArray readBytes(String readPointName, String id, int memoryBank, int offset, int length,
			String[] passwords) throws ReadPointNotFoundException, OutOfBoundsException, HardwareException,
			UnsupportedOperationException {

		throw new UnsupportedOperationException("readBytes: Unsupported method");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#supportsReadBytes()
	 */
	public boolean supportsReadBytes() {

		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#writeBytes(java.lang.String,
	 * java.lang.String, int, int, org.fosstrak.hal.UnsignedByteArray,
	 * java.lang.String[])
	 */
	public void writeBytes(String readPointName, String id, int memoryBank, int offset, UnsignedByteArray data,
			String[] passwords) throws ReadPointNotFoundException, OutOfBoundsException, HardwareException,
			UnsupportedOperationException {

		throw new UnsupportedOperationException("writeBytes: Unsupported method");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#supportsWriteBytes()
	 */
	public boolean supportsWriteBytes() {

		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#kill(java.lang.String,
	 * java.lang.String, java.lang.String[])
	 */
	public void kill(String readPointName, String id, String[] passwords) throws ReadPointNotFoundException,
			HardwareException, UnsupportedOperationException {

		throw new UnsupportedOperationException("kill: Unsupported method (temporarily)");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#supportsKill()
	 */
	public boolean supportsKill() {

		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#writeId(java.lang.String,
	 * java.lang.String, java.lang.String[])
	 */
	public void writeId(String readPointName, String id, String[] passwords) throws ReadPointNotFoundException,
			HardwareException, UnsupportedOperationException {

		throw new UnsupportedOperationException("writeId: Unsupported method");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#supportsWriteId()
	 */
	public boolean supportsWriteId() {

		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#getHALName()
	 */
	public String getHALName() {
		return halName;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#getReadPointNames()
	 */
	public String[] getReadPointNames() {

		return logicalSources.keySet().toArray(new String[logicalSources.size()]);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#getAllParameterNames()
	 */
	public String[] getAllParameterNames() throws HardwareException, UnsupportedOperationException {
		try {
			@SuppressWarnings("rawtypes")
			Iterator it = config.getKeys();
			List<String> names = new Vector<String>();
			Object item;

			while (it.hasNext()) {
				item = it.next();

				if (String.class.isInstance(item)) {
					names.add((String) item);
				}
			}

			String[] namesarray = new String[names.size()];
			namesarray = names.toArray(namesarray);

			return namesarray;

		} catch (Exception e) {
			log.error("getAllParameterNames: Error gettings parameter names", e);
			throw new HardwareException("Error getting parameter names", e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#getParameter(java.lang.String)
	 */
	public String getParameter(String param) throws HardwareException, UnsupportedOperationException {
		try {
			// config file parameter
			String value = config.getString(param);
			return value;

		} catch (Exception e) {
			log.error("getParameter: Error getting parameter", e);
			throw new HardwareException("Error getting parameter", e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#setParameter(java.lang.String,
	 * java.lang.String)
	 */
	public void setParameter(String param, String value) throws HardwareException, UnsupportedOperationException {
		// Nothing to change, changing runtime parameters is no supported
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#supportsParameters()
	 */
	public boolean supportsParameters() {
		return true;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#reset()
	 */
	public void reset() throws HardwareException {

		if (connector != null) {
			try {
				connector.close();

			} catch (IOException e) {
			}
		}

		try {
			initReader();

		} catch (HardwareException e) {
			log.error("reset: Error performing a reset to the reader", e);
			throw new HardwareException("Error performing a reset to the reader", e);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#supportsReset()
	 */
	public boolean supportsReset() {
		return true;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.fosstrak.hal.HardwareAbstraction#getReadPointPowerLevel(java.lang
	 * .String, boolean)
	 */
	public int getReadPointPowerLevel(String readPointName, boolean normalize) throws ReadPointNotFoundException,
			HardwareException, UnsupportedOperationException {

		return 0;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.fosstrak.hal.HardwareAbstraction#supportsGetReadPointPowerLevel()
	 */
	public boolean supportsGetReadPointPowerLevel() {

		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.fosstrak.hal.HardwareAbstraction#getReadPointNoiseLevel(java.lang
	 * .String, boolean)
	 */
	public int getReadPointNoiseLevel(String readPointName, boolean normalize) throws ReadPointNotFoundException,
			HardwareException, UnsupportedOperationException {

		throw new UnsupportedOperationException("getReadPointNoiseLevel: Unsupported method");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.fosstrak.hal.HardwareAbstraction#supportsGetReadPointNoiseLevel()
	 */
	public boolean supportsGetReadPointNoiseLevel() {

		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.fosstrak.hal.HardwareAbstraction#startUpReadPoint(java.lang.String)
	 */
	public void startUpReadPoint(String readPointName) throws ReadPointNotFoundException, HardwareException,
			UnsupportedOperationException {

		throw new UnsupportedOperationException("startUpReadPoint: Unsupported method");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#supportsStartUpReadPoint()
	 */
	public boolean supportsStartUpReadPoint() {

		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.fosstrak.hal.HardwareAbstraction#shutDownReadPoint(java.lang.String)
	 */
	public void shutDownReadPoint(String readPointName) throws ReadPointNotFoundException, HardwareException,
			UnsupportedOperationException {

		throw new UnsupportedOperationException("shutDownReadPoint: Unsupported method");
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#supportsShutDownReadPoint()
	 */
	public boolean supportsShutDownReadPoint() {

		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.fosstrak.hal.HardwareAbstraction#isReadPointReady(java.lang.String)
	 */
	public boolean isReadPointReady(String readPointName) throws ReadPointNotFoundException, HardwareException,
			UnsupportedOperationException {

		for (String rp : getReadPointNames()) {
			if (rp.equalsIgnoreCase(readPointName))
				return true;
		}

		return false;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.fosstrak.hal.HardwareAbstraction#supportsIsReadPointReady()
	 */
	public boolean supportsIsReadPointReady() {

		return true;
	}

}
