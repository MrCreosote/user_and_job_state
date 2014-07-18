package us.kbase.userandjobstate.test;


import java.net.UnknownHostException;
import java.util.logging.Level;
import java.util.logging.Logger;

import us.kbase.common.mongo.exceptions.InvalidHostException;
import us.kbase.common.test.TestException;

import com.mongodb.DB;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.MongoException;

public class UserJobStateTestCommon {
	
	public static final String DB = "test.mongo.db";
	public static final String HOST = "test.mongo.host";
	public static final String M_USER = "test.mongo.user";
	public static final String M_PWD = "test.mongo.pwd";
	public static final String AWE_DB = "test.awe.db";
	public static final String SHOCK_URL = "test.shock.url";
	public static final String AWE_EXE = "test.awe.server.exe";
	public static final String AWEC_EXE = "test.awe.client.exe";
			
	private static MongoClient mongoClient = null;
	
	public static void printJava() {
		System.out.println("Java: " + System.getProperty("java.runtime.version"));
	}
	
	private static String getProp(String prop) {
		String p = System.getProperty(prop);
		if (p == null || p.isEmpty()) {
			throw new TestException("Property " + prop +
					" cannot be null or the empty string.");
		}
		return p;
	}
	
	public static String getHost() {
		return getProp(HOST);
	}
	
	public static String getMongoUser() {
		return System.getProperty(M_USER);
	}
	
	public static String getMongoPwd() {
		return System.getProperty(M_PWD);
	}
	
	public static String getDB() {
		return getProp(DB);
	}
	
	public static String getAweDB() {
		return getProp(AWE_DB);
	}
	
	public static String getAweExe() {
		return getProp(AWE_EXE);
	}
	
	public static String getAweClientExe() {
		return getProp(AWEC_EXE);
	}
	
	public static String getShockUrl() {
		return getProp(SHOCK_URL);
	}
	
	private static void buildMongo() throws UnknownHostException,
			InvalidHostException, TestException {
		if (mongoClient != null) {
			return;
		}
		printJava();
		String host = getHost();
		String mUser = getMongoUser();
		String mPwd = getMongoPwd();
		if (mUser == null || mUser.equals("")) {
			mUser = null;
		}
		if (mPwd == null || mPwd.equals("")) {
			mPwd = null;
		}
		if (mUser == null ^ mPwd == null) {
			throw new TestException(String.format("Must provide both %s and %s ",
					M_USER, M_PWD) + "params for testing if authentication " + 
					"is to be used");
		}
		System.out.print("Mongo auth params are user: " + mUser + " pwd: ");
		if (mPwd != null && mPwd.length() > 0) {
			System.out.println("[redacted]");
		} else {
			System.out.println(mPwd);
		}
		if (mongoClient == null) {
			Logger.getLogger("com.mongodb").setLevel(Level.OFF);
			final MongoClientOptions opts = MongoClientOptions.builder()
					.autoConnectRetry(true).build();
			try {
				mongoClient = new MongoClient(host, opts);
			} catch (NumberFormatException nfe) {
				throw new InvalidHostException(host
						+ " is not a valid mongodb host");
			}
		}
		System.out.println("Created static mongo client pointed at: " + host);
	}
	
	//run this method first, lots of error checking
	public static DB destroyAndSetupDB()
			throws InvalidHostException, UnknownHostException, TestException {
		String db = getDB();
		if (db == null) {
			throw new TestException("The property " + DB + " is not set.");
		}
		return destroyAndSetupDB(db);
	}

	private static DB destroyAndSetupDB(String db) throws UnknownHostException,
			InvalidHostException {
		buildMongo();
		String mUser = getMongoUser();
		String mPwd = getMongoPwd();
		System.out.print(String.format("Destroying mongo database %s at %s...",
				db, getHost()));
		DB mdb;
		try {
			mdb = mongoClient.getDB(db);
			if (mUser != null) {
				mdb.authenticate(mUser, mPwd.toCharArray());
			}
		} catch (MongoException.Network men) {
			throw new TestException("Error connecting to mongodb test instance: "
					+ men.getCause().getLocalizedMessage());
		}
		try {
			for (String name: mdb.getCollectionNames()) {
				if (!name.startsWith("system.")) {
					mdb.getCollection(name).drop();
				}
			}
		} catch (MongoException me) {
			throw new TestException("\nCould not delete the database. Please grant " + 
					"read/write access to the database or correct the credentials:\n" +
					me.getLocalizedMessage());
		}
		System.out.println(" buhbye.");
		return mdb;
	}
	
	public static DB destroyAndSetupAweDB()
			throws InvalidHostException, UnknownHostException, TestException {
		String db = getAweDB();
		if (db == null) {
			throw new TestException("The property " + AWE_DB + " is not set.");
		}
		return destroyAndSetupDB(db);
	}
}
