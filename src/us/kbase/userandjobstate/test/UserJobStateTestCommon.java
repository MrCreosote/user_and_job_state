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
			
	private static MongoClient mongoClient = null;
	
	public static void printJava() {
		System.out.println("Java: " + System.getProperty("java.runtime.version"));
	}
	
	private static String getProp(String prop) {
		if (System.getProperty(prop) == null) {
			throw new TestException("Property " + prop + " cannot be null.");
		}
		return System.getProperty(prop);
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
			System.err.println(String.format("Must provide both %s and %s ",
					M_USER, M_PWD) + "params for testing if authentication " + 
					"is to be used");
			System.exit(1);
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
		buildMongo();
		String db = System.getProperty(DB);
		if (db == null) {
			throw new TestException("The property " + DB + " is not set.");
		}
		String mUser = getMongoUser();
		String mPwd = getMongoPwd();
		System.out.print(String.format("Destroying mongo database %s at %s...",
				db, getHost()));
		mongoClient.dropDatabase(db);
		System.out.println(" buhbye.");
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
		return mdb;
	}
}