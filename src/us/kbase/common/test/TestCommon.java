package us.kbase.common.test;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.ini4j.Ini;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;

import us.kbase.auth.AuthException;
import us.kbase.auth.AuthToken;
import us.kbase.auth.ConfigurableAuthService;
import us.kbase.common.test.TestException;

public class TestCommon {
	
	public static final String AUTHSERV = "test.auth.url";
	public static final String GLOBUS = "test.globus.url";
	
	public static final String MONGOEXE = "test.mongo.exe";
	public static final String MONGO_USE_WIRED_TIGER = "test.mongo.useWiredTiger";
	
	public static final String TEST_TEMP_DIR = "test.temp.dir";
	public static final String KEEP_TEMP_DIR = "test.temp.dir.keep";
	
	public static final String JARS_PATH = "test.jars.dir";
	
	public static final String TEST_TOKEN_PREFIX = "test.token";
	
	public static final String TEST_CONFIG_FILE_PROP_NAME = "test.cfg";
	public static final String TEST_CONFIG_FILE_SECTION = "UserJobTest";
	
	private static Map<String, String> testConfig = null;
			
	public static void stfuLoggers() {
		((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
				.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
			.setLevel(ch.qos.logback.classic.Level.OFF);
		java.util.logging.Logger.getLogger("com.mongodb")
			.setLevel(java.util.logging.Level.OFF);
	}
	
	public static void printJava() {
		System.out.println("Java: " +
				System.getProperty("java.runtime.version"));
	}
	
	public static String getTestProperty(final String propertyKey) {
		getTestConfig();
		final String prop = testConfig.get(propertyKey);
		if (prop == null || prop.trim().isEmpty()) {
			throw new TestException(String.format(
					"Property %s in section %s of test file %s is missing",
					propertyKey, TEST_CONFIG_FILE_SECTION, getConfigFilePath()));
		}
		return prop;
	}

	private static void getTestConfig() {
		if (testConfig != null) {
			return;
		}
		final Path testCfgFilePath = getConfigFilePath();
		final Ini ini;
		try {
			ini = new Ini(testCfgFilePath.toFile());
		} catch (IOException ioe) {
			throw new TestException(String.format(
					"IO Error reading the test configuration file %s: %s",
					testCfgFilePath, ioe.getMessage()), ioe);
		}
		testConfig = ini.get(TEST_CONFIG_FILE_SECTION);
		if (testConfig == null) {
			throw new TestException(String.format("No section %s found in test config file %s",
					TEST_CONFIG_FILE_SECTION, testCfgFilePath));
		}
	}

	private static Path getConfigFilePath() {
		final String testCfgFilePathStr = System.getProperty(TEST_CONFIG_FILE_PROP_NAME);
		if (testCfgFilePathStr == null || testCfgFilePathStr.trim().isEmpty()) {
			throw new TestException(String.format("Cannot get the test config file path." +
					" Ensure the java system property %s is set to the test config file location.",
					TEST_CONFIG_FILE_PROP_NAME));
		}
		return Paths.get(testCfgFilePathStr).toAbsolutePath().normalize();
	}
	
	public static AuthToken getToken(
			final int user,
			final ConfigurableAuthService auth) {
		try {
			return auth.validateToken(getToken(user));
		} catch (AuthException | IOException e) {
			throw new TestException(String.format(
					"Couldn't log in user #%s with token: %s", user, e.getMessage()), e);
		}
	}
	
	public static String getToken(final int user) {
		return getTestProperty(TEST_TOKEN_PREFIX + user);
	}
	
	public static URL getAuthUrl() {
		return getURL(AUTHSERV);
	}
	
	private static URL getURL(String prop) {
		try {
			return new URL(getTestProperty(prop));
		} catch (MalformedURLException e) {
			throw new TestException("Property " + prop + " is not a valid url",
					e);
		}
	}
	
	public static URL getGlobusUrl() {
		return getURL(GLOBUS);
	}
	
	public static String getTempDir() {
		return getTestProperty(TEST_TEMP_DIR);
	}
	
	public static String getMongoExe() {
		return getTestProperty(MONGOEXE);
	}
	
	public static boolean useWiredTigerEngine() {
		return "true".equals(getTestProperty(MONGO_USE_WIRED_TIGER));
	}
	
	public static Path getJarsDir() {
		return Paths.get(getTestProperty(JARS_PATH));
	}
	
	public static boolean getDeleteTempFiles() {
		return !"true".equals(System.getProperty(KEEP_TEMP_DIR));
	}
	
	public static void destroyDB(DB db) {
		for (String name: db.getCollectionNames()) {
			if (!name.startsWith("system.")) {
				// dropping collection also drops indexes
				db.getCollection(name).remove(new BasicDBObject());
			}
		}
	}
	
	public static void assertExceptionCorrect(
			final Exception got,
			final Exception expected) {
		assertThat("incorrect exception. trace:\n" +
				ExceptionUtils.getStackTrace(got),
				got.getLocalizedMessage(),
				is(expected.getLocalizedMessage()));
		assertThat("incorrect exception type", got, is(expected.getClass()));
	}
	
	//http://quirkygba.blogspot.com/2009/11/setting-environment-variables-in-java.html
	@SuppressWarnings("unchecked")
	public static Map<String, String> getenv()
			throws NoSuchFieldException, SecurityException,
			IllegalArgumentException, IllegalAccessException {
		Map<String, String> unmodifiable = System.getenv();
		Class<?> cu = unmodifiable.getClass();
		Field m = cu.getDeclaredField("m");
		m.setAccessible(true);
		return (Map<String, String>) m.get(unmodifiable);
	}
}
