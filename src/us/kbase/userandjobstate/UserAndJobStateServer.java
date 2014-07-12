package us.kbase.userandjobstate;

import java.util.List;
import us.kbase.auth.AuthToken;
import us.kbase.common.service.JsonServerMethod;
import us.kbase.common.service.JsonServerServlet;
import us.kbase.common.service.Tuple14;
import us.kbase.common.service.Tuple2;
import us.kbase.common.service.Tuple5;
import us.kbase.common.service.Tuple7;
import us.kbase.common.service.UObject;

//BEGIN_HEADER
import static us.kbase.common.utils.ServiceUtils.checkAddlArgs;
import static us.kbase.common.utils.StringUtils.checkMaxLen;
import static us.kbase.common.utils.StringUtils.checkString;
import static us.kbase.userandjobstate.jobstate.JobResults.MAX_LEN_ID;
import static us.kbase.userandjobstate.jobstate.JobResults.MAX_LEN_URL;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormatterBuilder;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import us.kbase.auth.AuthException;
import us.kbase.auth.AuthService;
import us.kbase.auth.TokenExpiredException;
import us.kbase.auth.TokenFormatException;
import us.kbase.common.mongo.exceptions.InvalidHostException;
import us.kbase.common.mongo.exceptions.MongoAuthException;
import us.kbase.userandjobstate.awe.AweJobState;
import us.kbase.userandjobstate.awe.client.AweJobId;
import us.kbase.userandjobstate.awe.client.exceptions.AweHttpException;
import us.kbase.userandjobstate.awe.client.exceptions.InvalidAweUrlException;
import us.kbase.userandjobstate.jobstate.Job;
import us.kbase.userandjobstate.jobstate.JobResult;
import us.kbase.userandjobstate.jobstate.JobResults;
import us.kbase.userandjobstate.jobstate.UJSJobState;
import us.kbase.userandjobstate.jobstate.JobState;
import us.kbase.userandjobstate.userstate.UserState;
import us.kbase.userandjobstate.userstate.UserState.KeyState;
//END_HEADER

/**
 * <p>Original spec-file module name: UserAndJobState</p>
 * <pre>
 * Service for storing arbitrary key/object pairs on a per user per service basis
 * and storing job status so that a) long JSON RPC calls can report status and
 * UI elements can receive updates, and b) there's a centralized location for 
 * job status reporting.
 * There are two modes of operation for setting key values for a user: 
 * 1) no service authentication - an authorization token for a service is not 
 *         required, and any service with the user token can write to any other
 *         service's unauthed values for that user.
 * 2) service authentication required - the service must pass a Globus Online
 *         token that identifies the service in the argument list. Values can only be
 *         set by services with possession of a valid token. The service name 
 *         will be set to the username of the token.
 * The sets of key/value pairs for the two types of method calls are entirely
 * separate - for example, the workspace service could have a key called 'default'
 * that is writable by all other services (no auth) and the same key that was 
 * set with auth to which only the workspace service can write (or any other
 * service that has access to a workspace service account token, so keep your
 * service credentials safe).
 * Setting objects are limited to 640Kb.
 * All job writes require service authentication. No reads, either for key/value
 * pairs or jobs, require service authentication.
 * The service assumes other services are capable of simple math and does not
 * throw errors if a progress bar overflows.
 * Jobs are automatically deleted after 30 days.
 * Potential job process flows:
 * Asysnc:
 * UI calls service function which returns with job id
 * service call [spawns thread/subprocess to run job that] periodically updates
 *         the job status of the job id on the job status server
 * meanwhile, the UI periodically polls the job status server to get progress
 *         updates
 * service call finishes, completes job
 * UI pulls pointers to results from the job status server
 * Sync:
 * UI creates job, gets job id
 * UI starts thread that calls service, providing job id
 * service call runs, periodically updating the job status of the job id on the
 *         job status server
 * meanwhile, the UI periodically polls the job status server to get progress
 *         updates
 * service call finishes, completes job, returns results
 * UI thread joins
 * </pre>
 */
public class UserAndJobStateServer extends JsonServerServlet {
    private static final long serialVersionUID = 1L;

    //BEGIN_CLASS_HEADER
	
    //TODO needs to look through the AWE code and look for cruft, this was written in haste
    //TODO a full suite of tests for AWE integration

	private static final String VER = "0.0.5";

	//required deploy parameters:
	private static final String HOST = "mongodb-host";
	private static final String DB = "mongodb-database";
	//auth params:
	private static final String USER = "mongodb-user";
	private static final String PWD = "mongodb-pwd";
	//mongo connection attempt limit
	private static final String MONGO_RECONNECT = "mongodb-retry";
	//awe url
	private static final String AWE_URL = "awe-url";
	
	private static Map<String, String> ujConfig = null;
	
	private static final String USER_COLLECTION = "userstate";
	private static final String JOB_COLLECTION = "jobstate";
	
	public final static int MAX_LEN_SERVTYPE = 100;
	public final static int MAX_LEN_DESC = 1000;
	
	private final UserState us;
	private final JobState js;
	private final URL aweUrl;
	
	private final static DateTimeFormatter DATE_PARSER =
			new DateTimeFormatterBuilder()
				.append(DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss"))
				.appendOptional(DateTimeFormat.forPattern(".SSS").getParser())
				.append(DateTimeFormat.forPattern("Z"))
				.toFormatter();
	
	private final static DateTimeFormatter DATE_FORMATTER =
			DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ssZ").withZoneUTC();
	
	private UserState getUserState(final String host, final String dbs,
			final String user, final String pwd,
			final int mongoReconnectRetry) {
		try {
			if (user != null) {
				return new UserState(host, dbs, USER_COLLECTION, user, pwd,
						mongoReconnectRetry);
			} else {
				return new UserState(host, dbs, USER_COLLECTION,
						mongoReconnectRetry);
			}
		} catch (UnknownHostException uhe) {
			fail("Couldn't find mongo host " + host + ": " +
					uhe.getLocalizedMessage());
		} catch (IOException io) {
			fail("Couldn't connect to mongo host " + host + ": " +
					io.getLocalizedMessage());
		} catch (MongoAuthException ae) {
			fail("Not authorized: " + ae.getLocalizedMessage());
		} catch (InvalidHostException ihe) {
			fail(host + " is an invalid database host: "  +
					ihe.getLocalizedMessage());
		} catch (InterruptedException ie) {
			fail("Connection to MongoDB was interrupted. This should never " +
					"happen and indicates a programming problem. Error: " +
					ie.getLocalizedMessage());
		}
		return null;
	}
	
	private JobState getJobState(final String host, final String dbs,
			final String user, final String pwd,
			final int mongoReconnectRetry) {
		try {
			if (user != null) {
				return new UJSJobState(host, dbs, JOB_COLLECTION, user, pwd,
						mongoReconnectRetry);
			} else {
				return new UJSJobState(host, dbs, JOB_COLLECTION,
						mongoReconnectRetry);
			}
		} catch (UnknownHostException uhe) {
			fail("Couldn't find mongo host " + host + ": " +
					uhe.getLocalizedMessage());
		} catch (IOException io) {
			fail("Couldn't connect to mongo host " + host + ": " +
					io.getLocalizedMessage());
		} catch (MongoAuthException ae) {
			fail("Not authorized: " + ae.getLocalizedMessage());
		} catch (InvalidHostException ihe) {
			fail(host + " is an invalid database host: "  +
					ihe.getLocalizedMessage());
		} catch (InterruptedException ie) {
			fail("Connection to MongoDB was interrupted. This should never " +
					"happen and indicates a programming problem. Error: " +
					ie.getLocalizedMessage());
		}
		return null;
	}
	
	private URL checkAweUrl(final String host) {
		if (host == null || host.isEmpty()) {
			System.out.println("No Awe URL found in config, running without Awe server");
			logInfo("No Awe URL found in config, running without Awe server");
			return null;
		}
		try {
			final URL url = new URL(host);
			AweJobState.testURL(url);
			System.out.println("Connected to Awe server at " +
					url.toExternalForm());
			logInfo("Connected to Awe server at " + url.toExternalForm());
			return url;
		} catch (MalformedURLException mue) {
			fail("Invalid Awe url: " + mue.getLocalizedMessage());
		} catch (IOException io) {
			fail("Couldn't connect to awe server at " + host + ": " +
					io.getLocalizedMessage());
		} catch (InvalidAweUrlException e) {
			fail("Invalid Awe url: " + e.getLocalizedMessage());
		}
		return null;
	}
	
	private JobState getJobState(final String jobid, final AuthToken token)
			throws IOException, TokenExpiredException {
		// this is a filthy hack, but it'll do for now. Adding other job
		// runners will be problematic unless they all use globally unique
		//job ids
		try {
			new AweJobId(jobid);
		} catch (IllegalArgumentException iae) {
			return js;
		}
		try {
			return new AweJobState(aweUrl, token);
		} catch (IOException io) {
			throw new IOException("Couldn't connect to awe server at " +
					aweUrl, io);
		} catch (InvalidAweUrlException e) {
			throw new IOException("Couldn't connect to awe server at " +
					aweUrl, e);
		} catch (AweHttpException e) {
			throw new IOException("Couldn't connect to awe server at " +
					aweUrl, e);
		}
	}
	
	private JobState getAweJobState(final AuthToken token)
			throws IOException, TokenExpiredException {
		try {
			return new AweJobState(aweUrl, token);
		} catch (IOException io) {
			throw new IOException("Couldn't connect to awe server at " +
					aweUrl, io);
		} catch (InvalidAweUrlException e) {
			throw new IOException("Couldn't connect to awe server at " +
					aweUrl, e);
		} catch (AweHttpException e) {
			throw new IOException("Couldn't connect to awe server at " +
					aweUrl, e);
		}
	}
	
	private void fail(final String error) {
		logErr(error);
		System.err.println(error);
		startupFailed();
	}
	
	private static String getServiceName(String serviceToken)
			throws TokenFormatException, TokenExpiredException, IOException {
		if (serviceToken == null || serviceToken.isEmpty()) {
			throw new IllegalArgumentException(
					"Service token cannot be null or the empty string");
		}
		final AuthToken t = new AuthToken(serviceToken);
		if (!AuthService.validateToken(t)) {
			throw new IllegalArgumentException("Service token is invalid");
		}
		return t.getUserName();
	}
	
	private Tuple14<String, String, String, String, String, String, Long,
			Long, String, String, Long, Long, String, Results>
			jobToJobInfo(final Job j) {
		return new Tuple14<String, String, String, String, String, String,
				Long, Long, String, String, Long, Long, String,
				Results>()
				.withE1(j.getID())
				.withE2(j.getService())
				.withE3(j.getStage())
				.withE4(formatDate(j.getStarted()))
				.withE5(j.getStatus())
				.withE6(formatDate(j.getLastUpdated()))
				.withE7(j.getProgress() == null ? null :
					new Long(j.getProgress()))
				.withE8(j.getMaxProgress() == null ? null :
					new Long(j.getMaxProgress()))
				.withE9(j.getProgType())
				.withE10(formatDate(j.getEstimatedCompletion()))
				.withE11(boolToLong(j.isComplete()))
				.withE12(boolToLong(j.hasError()))
				.withE13(j.getDescription())
				.withE14(makeResults(j.getResults()));
	}
	
	private static Long boolToLong(final Boolean b) {
		if (b == null) {
			return null;
		}
		return b ? 1L : 0L;
	}
	
	private static Results makeResults(final JobResults res) {
		if (res == null) {
			return null;
		}
		final List<Result> r;
		if (res.getResults() != null) {
			r = new LinkedList<Result>();
			for (final JobResult jr: res.getResults()) {
				r.add(new Result()
				.withServerType(jr.getServtype())
				.withUrl(jr.getUrl())
				.withId(jr.getId())
				.withDescription(jr.getDesc()));
			}
		} else {
			r = null;
		}
		return new Results()
				.withShocknodes((List<String>) res.getShocknodes())
				.withShockurl((String)res.getShockurl())
				.withWorkspaceids((List<String>) res.getWorkspaceids())
				.withWorkspaceurl((String) res.getWorkspaceurl())
				.withResults(r);
	}
	
	private static JobResults unmakeResults(Results res) {
		if (res == null) {
			return null;
		}
		checkAddlArgs(res.getAdditionalProperties(), Results.class);
		final List<JobResult> jrs;
		if (res.getResults() != null) {
			jrs = new LinkedList<JobResult>();
			for (final Result r: res.getResults()) {
				checkAddlArgs(r.getAdditionalProperties(), Result.class);
				//TODO tests for max lenghts, nulls, empty strings for Result contents
				checkString(r.getServerType(), "servtype", MAX_LEN_SERVTYPE);
				checkString(r.getUrl(), "url", MAX_LEN_URL);
				checkString(r.getId(), "id", MAX_LEN_ID);
				checkMaxLen(r.getDescription(), "description", MAX_LEN_DESC);
				jrs.add(new JobResult(r.getServerType(), r.getUrl(), r.getId(),
						r.getDescription()));
			}
		} else {
			jrs = null;
		}
		return new JobResults(jrs,
				res.getWorkspaceurl(),
				res.getWorkspaceids(),
				res.getShockurl(),
				res.getShocknodes());
	}
		
	private Date parseDate(final String date) {
		if (date == null) {
			return null;
		}
		try {
			return DATE_PARSER.parseDateTime(date).toDate();
		} catch (IllegalArgumentException iae) {
			throw new IllegalArgumentException("Unparseable date: " +
					iae.getMessage());
		}
	}
	
	private String formatDate(final Date date) {
		return date == null ? null : DATE_FORMATTER.print(new DateTime(date));
	}
	
	private void checkUsers(final List<String> users, AuthToken token)
			throws IOException, AuthException {
		//token is guaranteed to not be null since all calls require
		//authentication
		if (users == null || users.isEmpty()) {
			throw new IllegalArgumentException(
					"The user list may not be null or empty");
		}
		final Map<String, Boolean> userok = AuthService.isValidUserName(
				users, token);
		for (String u: userok.keySet()) {
			if (!userok.get(u)) {
				throw new IllegalArgumentException(String.format(
						"User %s is not a valid user", u));
			}
		}
	}
	
	public void setUpLogger() {
		((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME))
				.setLevel(Level.OFF);
		final Logger kbaseRootLogger = (Logger) LoggerFactory.getLogger(
				"us.kbase");
		//would be better to also set the level here on calls to the server
		//setLogLevel, but meh for now
		kbaseRootLogger.setLevel(Level.ALL);
		final AppenderBase<ILoggingEvent> kbaseAppender =
				new AppenderBase<ILoggingEvent>() {

			@Override
			protected void append(final ILoggingEvent event) {
				//for now only INFO is tested; test others as they're needed
				final Level l = event.getLevel();
				if (l.equals(Level.TRACE)) {
					logDebug(event.getFormattedMessage(), 3);
				} else if (l.equals(Level.DEBUG)) {
					logDebug(event.getFormattedMessage());
				} else if (l.equals(Level.INFO) || l.equals(Level.WARN)) {
					logInfo(event.getFormattedMessage());
				} else if (l.equals(Level.ERROR)) {
					logErr(event.getFormattedMessage());
				}
			}
		};
		kbaseAppender.start();
		kbaseRootLogger.addAppender(kbaseAppender);
	}
	
	private int getReconnectCount() {
		final String rec = ujConfig.get(MONGO_RECONNECT);
		Integer recint = null;
		try {
			recint = Integer.parseInt(rec); 
		} catch (NumberFormatException nfe) {
			//do nothing
		}
		if (recint == null) {
			logInfo("Couldn't parse MongoDB reconnect value to an integer: " +
					rec + ", using 0");
			recint = 0;
		} else if (recint < 0) {
			logInfo("MongoDB reconnect value is < 0 (" + recint + "), using 0");
			recint = 0;
		} else {
			logInfo("MongoDB reconnect value is " + recint);
		}
		return recint;
	}
    //END_CLASS_HEADER

    public UserAndJobStateServer() throws Exception {
        super("UserAndJobState");
        //BEGIN_CONSTRUCTOR
		//assign config once per jvm, otherwise you could wind up with
		//different threads talking to different mongo instances
		//E.g. first thread's config applies to all threads.
		if (ujConfig == null) {
			ujConfig = new HashMap<String, String>();
			ujConfig.putAll(super.config);
		}
		setUpLogger();
		boolean failed = false;
		if (!ujConfig.containsKey(HOST)) {
			fail("Must provide param " + HOST + " in config file");
			failed = true;
		}
		final String host = ujConfig.get(HOST);
		if (!ujConfig.containsKey(DB)) {
			fail("Must provide param " + DB + " in config file");
			failed = true;
		}
		final String dbs = ujConfig.get(DB);
		if (ujConfig.containsKey(USER) ^ ujConfig.containsKey(PWD)) {
			fail(String.format("Must provide both %s and %s ",
					USER, PWD) + "params in config file if authentication " + 
					"is to be used");
			failed = true;
		}
		if (failed) {
			fail("Server startup failed - all calls will error out.");
			us = null;
			js = null;
			aweUrl = null;
		} else {
			final String user = ujConfig.get(USER);
			final String pwd = ujConfig.get(PWD);
			String params = "";
			for (String s: Arrays.asList(HOST, DB, USER)) {
				if (ujConfig.containsKey(s)) {
					params += s + "=" + ujConfig.get(s) + "\n";
				}
			}
			if (pwd != null) {
				params += PWD + "=[redacted for your safety and comfort]\n";
			}
			System.out.println("Starting server using connection parameters:\n"
					+ params);
			logInfo("Starting server using connection parameters:\n" + params);
			final int mongoConnectRetry = getReconnectCount();
			us = getUserState(host, dbs, user, pwd, mongoConnectRetry);
			js = getJobState(host, dbs, user, pwd, mongoConnectRetry);
			final String aweUrlString = ujConfig.get(AWE_URL);
			aweUrl = checkAweUrl(aweUrlString);
		}
        //END_CONSTRUCTOR
    }

    /**
     * <p>Original spec-file function name: ver</p>
     * <pre>
     * Returns the version of the userandjobstate service.
     * </pre>
     * @return   parameter "ver" of String
     */
    @JsonServerMethod(rpc = "UserAndJobState.ver")
    public String ver() throws Exception {
        String returnVal = null;
        //BEGIN ver
		returnVal = VER;
        //END ver
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: set_state</p>
     * <pre>
     * Set the state of a key for a service without service authentication.
     * </pre>
     * @param   service   instance of original type "service_name" (A service name. Alphanumerics and the underscore are allowed.)
     * @param   key   instance of String
     * @param   value   instance of unspecified object
     */
    @JsonServerMethod(rpc = "UserAndJobState.set_state")
    public void setState(String service, String key, UObject value, AuthToken authPart) throws Exception {
        //BEGIN set_state
		us.setState(authPart.getUserName(), service, false, key,
				value == null ? null : value.asClassInstance(Object.class));
        //END set_state
    }

    /**
     * <p>Original spec-file function name: set_state_auth</p>
     * <pre>
     * Set the state of a key for a service with service authentication.
     * </pre>
     * @param   token   instance of original type "service_token" (A globus ID token that validates that the service really is said service.)
     * @param   key   instance of String
     * @param   value   instance of unspecified object
     */
    @JsonServerMethod(rpc = "UserAndJobState.set_state_auth")
    public void setStateAuth(String token, String key, UObject value, AuthToken authPart) throws Exception {
        //BEGIN set_state_auth
		us.setState(authPart.getUserName(), getServiceName(token), true, key,
				value == null ? null : value.asClassInstance(Object.class));
        //END set_state_auth
    }

    /**
     * <p>Original spec-file function name: get_state</p>
     * <pre>
     * Get the state of a key for a service.
     * </pre>
     * @param   service   instance of original type "service_name" (A service name. Alphanumerics and the underscore are allowed.)
     * @param   key   instance of String
     * @param   auth   instance of original type "authed" (Specifies whether results returned should be from key/value pairs set with service authentication (true) or without (false).) &rarr; original type "boolean" (A boolean. 0 = false, other = true.)
     * @return   parameter "value" of unspecified object
     */
    @JsonServerMethod(rpc = "UserAndJobState.get_state")
    public UObject getState(String service, String key, Long auth, AuthToken authPart) throws Exception {
        UObject returnVal = null;
        //BEGIN get_state
		returnVal = new UObject(us.getState(authPart.getUserName(), service,
				auth != 0, key));
        //END get_state
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: has_state</p>
     * <pre>
     * Determine if a key exists for a service.
     * </pre>
     * @param   service   instance of original type "service_name" (A service name. Alphanumerics and the underscore are allowed.)
     * @param   key   instance of String
     * @param   auth   instance of original type "authed" (Specifies whether results returned should be from key/value pairs set with service authentication (true) or without (false).) &rarr; original type "boolean" (A boolean. 0 = false, other = true.)
     * @return   parameter "has_key" of original type "boolean" (A boolean. 0 = false, other = true.)
     */
    @JsonServerMethod(rpc = "UserAndJobState.has_state")
    public Long hasState(String service, String key, Long auth, AuthToken authPart) throws Exception {
        Long returnVal = null;
        //BEGIN has_state
		returnVal = boolToLong(us.hasState(authPart.getUserName(), service,
				auth != 0, key));
        //END has_state
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_has_state</p>
     * <pre>
     * Get the state of a key for a service, and do not throw an error if the
     * key doesn't exist. If the key doesn't exist, has_key will be false
     * and the key value will be null.
     * </pre>
     * @param   service   instance of original type "service_name" (A service name. Alphanumerics and the underscore are allowed.)
     * @param   key   instance of String
     * @param   auth   instance of original type "authed" (Specifies whether results returned should be from key/value pairs set with service authentication (true) or without (false).) &rarr; original type "boolean" (A boolean. 0 = false, other = true.)
     * @return   multiple set: (1) parameter "has_key" of original type "boolean" (A boolean. 0 = false, other = true.), (2) parameter "value" of unspecified object
     */
    @JsonServerMethod(rpc = "UserAndJobState.get_has_state", tuple = true)
    public Tuple2<Long, UObject> getHasState(String service, String key, Long auth, AuthToken authPart) throws Exception {
        Long return1 = null;
        UObject return2 = null;
        //BEGIN get_has_state
		final KeyState ks = us.getState(authPart.getUserName(), service,
				auth != 0, key, false);
		return1 = boolToLong(ks.exists());
		return2 = new UObject(ks.getValue());
        //END get_has_state
        Tuple2<Long, UObject> returnVal = new Tuple2<Long, UObject>();
        returnVal.setE1(return1);
        returnVal.setE2(return2);
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: remove_state</p>
     * <pre>
     * Remove a key value pair without service authentication.
     * </pre>
     * @param   service   instance of original type "service_name" (A service name. Alphanumerics and the underscore are allowed.)
     * @param   key   instance of String
     */
    @JsonServerMethod(rpc = "UserAndJobState.remove_state")
    public void removeState(String service, String key, AuthToken authPart) throws Exception {
        //BEGIN remove_state
		us.removeState(authPart.getUserName(), service, false, key);
        //END remove_state
    }

    /**
     * <p>Original spec-file function name: remove_state_auth</p>
     * <pre>
     * Remove a key value pair with service authentication.
     * </pre>
     * @param   token   instance of original type "service_token" (A globus ID token that validates that the service really is said service.)
     * @param   key   instance of String
     */
    @JsonServerMethod(rpc = "UserAndJobState.remove_state_auth")
    public void removeStateAuth(String token, String key, AuthToken authPart) throws Exception {
        //BEGIN remove_state_auth
		us.removeState(authPart.getUserName(), getServiceName(token), true,
				key);	
        //END remove_state_auth
    }

    /**
     * <p>Original spec-file function name: list_state</p>
     * <pre>
     * List all keys.
     * </pre>
     * @param   service   instance of original type "service_name" (A service name. Alphanumerics and the underscore are allowed.)
     * @param   auth   instance of original type "authed" (Specifies whether results returned should be from key/value pairs set with service authentication (true) or without (false).) &rarr; original type "boolean" (A boolean. 0 = false, other = true.)
     * @return   parameter "keys" of list of String
     */
    @JsonServerMethod(rpc = "UserAndJobState.list_state")
    public List<String> listState(String service, Long auth, AuthToken authPart) throws Exception {
        List<String> returnVal = null;
        //BEGIN list_state
		returnVal = new LinkedList<String>(us.listState(authPart.getUserName(),
				service, auth != 0));
        //END list_state
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: list_state_services</p>
     * <pre>
     * List all state services.
     * </pre>
     * @param   auth   instance of original type "authed" (Specifies whether results returned should be from key/value pairs set with service authentication (true) or without (false).) &rarr; original type "boolean" (A boolean. 0 = false, other = true.)
     * @return   parameter "services" of list of original type "service_name" (A service name. Alphanumerics and the underscore are allowed.)
     */
    @JsonServerMethod(rpc = "UserAndJobState.list_state_services")
    public List<String> listStateServices(Long auth, AuthToken authPart) throws Exception {
        List<String> returnVal = null;
        //BEGIN list_state_services
		returnVal = new LinkedList<String>(us.listServices(
				authPart.getUserName(), auth != 0));
        //END list_state_services
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: create_job</p>
     * <pre>
     * Create a new job status report.
     * </pre>
     * @return   parameter "job" of original type "job_id" (A job id.)
     */
    @JsonServerMethod(rpc = "UserAndJobState.create_job")
    public String createJob(AuthToken authPart) throws Exception {
        String returnVal = null;
        //BEGIN create_job
		returnVal = js.createJob(authPart.getUserName());
        //END create_job
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: start_job</p>
     * <pre>
     * Start a job and specify the job parameters.
     * </pre>
     * @param   job   instance of original type "job_id" (A job id.)
     * @param   token   instance of original type "service_token" (A globus ID token that validates that the service really is said service.)
     * @param   status   instance of original type "job_status" (A job status string supplied by the reporting service. No more than 200 characters.)
     * @param   desc   instance of original type "job_description" (A job description string supplied by the reporting service. No more than 1000 characters.)
     * @param   progress   instance of type {@link us.kbase.userandjobstate.InitProgress InitProgress}
     * @param   estComplete   instance of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time))
     */
    @JsonServerMethod(rpc = "UserAndJobState.start_job")
    public void startJob(String job, String token, String status, String desc, InitProgress progress, String estComplete, AuthToken authPart) throws Exception {
        //BEGIN start_job
		if (progress == null) {
			throw new IllegalArgumentException("InitProgress cannot be null");
		}
		checkAddlArgs(progress.getAdditionalProperties(), InitProgress.class);
		if (progress.getPtype() == null) {
			throw new IllegalArgumentException("Progress type cannot be null");
		}
		if (progress.getPtype().equals(JobState.PROG_NONE)) {
			js.startJob(authPart.getUserName(), job, getServiceName(token),
					status, desc, parseDate(estComplete));
		} else if (progress.getPtype().equals(JobState.PROG_PERC)) {
			js.startJobWithPercentProg(authPart.getUserName(), job,
					getServiceName(token), status, desc, parseDate(estComplete));
		} else if (progress.getPtype().equals(JobState.PROG_TASK)) {
			if (progress.getMax() == null) {
				throw new IllegalArgumentException(
						"Max progress cannot be null for task based progress");
			}
			if (progress.getMax().longValue() > Integer.MAX_VALUE) {
				throw new IllegalArgumentException(
						"Max progress can be no greater than "
						+ Integer.MAX_VALUE);
			}
			js.startJob(authPart.getUserName(), job, getServiceName(token),
					status, desc, (int) progress.getMax().longValue(),
					parseDate(estComplete));
		} else {
			throw new IllegalArgumentException("No such progress type: " +
					progress.getPtype());
		}
        //END start_job
    }

    /**
     * <p>Original spec-file function name: create_and_start_job</p>
     * <pre>
     * Create and start a job.
     * </pre>
     * @param   token   instance of original type "service_token" (A globus ID token that validates that the service really is said service.)
     * @param   status   instance of original type "job_status" (A job status string supplied by the reporting service. No more than 200 characters.)
     * @param   desc   instance of original type "job_description" (A job description string supplied by the reporting service. No more than 1000 characters.)
     * @param   progress   instance of type {@link us.kbase.userandjobstate.InitProgress InitProgress}
     * @param   estComplete   instance of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time))
     * @return   parameter "job" of original type "job_id" (A job id.)
     */
    @JsonServerMethod(rpc = "UserAndJobState.create_and_start_job")
    public String createAndStartJob(String token, String status, String desc, InitProgress progress, String estComplete, AuthToken authPart) throws Exception {
        String returnVal = null;
        //BEGIN create_and_start_job
		//could combine with above, but it'd be a huge mess
		if (progress == null) {
			throw new IllegalArgumentException("InitProgress cannot be null");
		}
		checkAddlArgs(progress.getAdditionalProperties(), InitProgress.class);
		if (progress.getPtype() == null) {
			throw new IllegalArgumentException("Progress type cannot be null");
		}
		if (progress.getPtype().equals(JobState.PROG_NONE)) {
			returnVal = js.createAndStartJob(authPart.getUserName(),
					getServiceName(token), status, desc, parseDate(estComplete));
		} else if (progress.getPtype().equals(JobState.PROG_PERC)) {
			returnVal = js.createAndStartJobWithPercentProg(
					authPart.getUserName(), getServiceName(token), status, desc,
					parseDate(estComplete));
		} else if (progress.getPtype().equals(JobState.PROG_TASK)) {
			if (progress.getMax() == null) {
				throw new IllegalArgumentException(
						"Max progress cannot be null for task based progress");
			}
			if (progress.getMax().longValue() > Integer.MAX_VALUE) {
				throw new IllegalArgumentException(
						"Max progress can be no greater than "
						+ Integer.MAX_VALUE);
			}
			returnVal = js.createAndStartJob(authPart.getUserName(),
					getServiceName(token), status, desc,
					(int) progress.getMax().longValue(),
					parseDate(estComplete));
		} else {
			throw new IllegalArgumentException("No such progress type: " +
					progress.getPtype());
		}
        //END create_and_start_job
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: update_job_progress</p>
     * <pre>
     * Update the status and progress for a job.
     * </pre>
     * @param   job   instance of original type "job_id" (A job id.)
     * @param   token   instance of original type "service_token" (A globus ID token that validates that the service really is said service.)
     * @param   status   instance of original type "job_status" (A job status string supplied by the reporting service. No more than 200 characters.)
     * @param   prog   instance of original type "progress" (The amount of progress the job has made since the last update. This will be summed to the total progress so far.)
     * @param   estComplete   instance of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time))
     */
    @JsonServerMethod(rpc = "UserAndJobState.update_job_progress")
    public void updateJobProgress(String job, String token, String status, Long prog, String estComplete, AuthToken authPart) throws Exception {
        //BEGIN update_job_progress
		Integer progval = null;
		if (prog != null) {
			if (prog.longValue() > Integer.MAX_VALUE) {
			throw new IllegalArgumentException(
					"Max progress can be no greater than "
					+ Integer.MAX_VALUE);
			}
			progval = (int) prog.longValue();
		}
		js.updateJob(authPart.getUserName(), job, getServiceName(token),
				status, progval, parseDate(estComplete));
        //END update_job_progress
    }

    /**
     * <p>Original spec-file function name: update_job</p>
     * <pre>
     * Update the status for a job.
     * </pre>
     * @param   job   instance of original type "job_id" (A job id.)
     * @param   token   instance of original type "service_token" (A globus ID token that validates that the service really is said service.)
     * @param   status   instance of original type "job_status" (A job status string supplied by the reporting service. No more than 200 characters.)
     * @param   estComplete   instance of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time))
     */
    @JsonServerMethod(rpc = "UserAndJobState.update_job")
    public void updateJob(String job, String token, String status, String estComplete, AuthToken authPart) throws Exception {
        //BEGIN update_job
		js.updateJob(authPart.getUserName(), job, getServiceName(token),
				status, null, parseDate(estComplete));
        //END update_job
    }

    /**
     * <p>Original spec-file function name: get_job_description</p>
     * <pre>
     * Get the description of a job.
     * </pre>
     * @param   job   instance of original type "job_id" (A job id.)
     * @return   multiple set: (1) parameter "service" of original type "service_name" (A service name. Alphanumerics and the underscore are allowed.), (2) parameter "ptype" of original type "progress_type" (The type of progress that is being tracked. One of: 'none' - no numerical progress tracking 'task' - Task based tracking, e.g. 3/24 'percent' - percentage based tracking, e.g. 5/100%), (3) parameter "max" of original type "max_progress" (The maximum possible progress of a job.), (4) parameter "desc" of original type "job_description" (A job description string supplied by the reporting service. No more than 1000 characters.), (5) parameter "started" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time))
     */
    @JsonServerMethod(rpc = "UserAndJobState.get_job_description", tuple = true)
    public Tuple5<String, String, Long, String, String> getJobDescription(String job, AuthToken authPart) throws Exception {
        String return1 = null;
        String return2 = null;
        Long return3 = null;
        String return4 = null;
        String return5 = null;
        //BEGIN get_job_description
		final Job j = getJobState(job, authPart).getJob(
				authPart.getUserName(), job);
		return1 = j.getService();
		return2 = j.getProgType();
		return3 = j.getMaxProgress() == null ? null :
			new Long(j.getMaxProgress());
		return4 = j.getDescription();
		return5 = formatDate(j.getStarted());
        //END get_job_description
        Tuple5<String, String, Long, String, String> returnVal = new Tuple5<String, String, Long, String, String>();
        returnVal.setE1(return1);
        returnVal.setE2(return2);
        returnVal.setE3(return3);
        returnVal.setE4(return4);
        returnVal.setE5(return5);
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_job_status</p>
     * <pre>
     * Get the status of a job.
     * </pre>
     * @param   job   instance of original type "job_id" (A job id.)
     * @return   multiple set: (1) parameter "last_update" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time)), (2) parameter "stage" of original type "job_stage" (A string that describes the stage of processing of the job. One of 'created', 'started', 'completed', or 'error'.), (3) parameter "status" of original type "job_status" (A job status string supplied by the reporting service. No more than 200 characters.), (4) parameter "progress" of original type "total_progress" (The total progress of a job.), (5) parameter "est_complete" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time)), (6) parameter "complete" of original type "boolean" (A boolean. 0 = false, other = true.), (7) parameter "error" of original type "boolean" (A boolean. 0 = false, other = true.)
     */
    @JsonServerMethod(rpc = "UserAndJobState.get_job_status", tuple = true)
    public Tuple7<String, String, String, Long, String, Long, Long> getJobStatus(String job, AuthToken authPart) throws Exception {
        String return1 = null;
        String return2 = null;
        String return3 = null;
        Long return4 = null;
        String return5 = null;
        Long return6 = null;
        Long return7 = null;
        //BEGIN get_job_status
		final Job j = getJobState(job, authPart).getJob(
				authPart.getUserName(), job);
		return1 = formatDate(j.getLastUpdated());
		return2 = j.getStage();
		return3 = j.getStatus();
		return4 = j.getProgress() == null ? null :new Long(j.getProgress());
		return5 = formatDate(j.getEstimatedCompletion());
		return6 = boolToLong(j.isComplete());
		return7 = boolToLong(j.hasError());
        //END get_job_status
        Tuple7<String, String, String, Long, String, Long, Long> returnVal = new Tuple7<String, String, String, Long, String, Long, Long>();
        returnVal.setE1(return1);
        returnVal.setE2(return2);
        returnVal.setE3(return3);
        returnVal.setE4(return4);
        returnVal.setE5(return5);
        returnVal.setE6(return6);
        returnVal.setE7(return7);
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: complete_job</p>
     * <pre>
     * Complete the job. After the job is completed, total_progress always
     * equals max_progress. If detailed_err is anything other than null,
     * the job is considered to have errored out.
     * </pre>
     * @param   job   instance of original type "job_id" (A job id.)
     * @param   token   instance of original type "service_token" (A globus ID token that validates that the service really is said service.)
     * @param   status   instance of original type "job_status" (A job status string supplied by the reporting service. No more than 200 characters.)
     * @param   error   instance of original type "detailed_err" (Detailed information about a job error, such as a stacktrace, that will not fit in the job_status. No more than 100K characters.)
     * @param   res   instance of type {@link us.kbase.userandjobstate.Results Results}
     */
    @JsonServerMethod(rpc = "UserAndJobState.complete_job")
    public void completeJob(String job, String token, String status, String error, Results res, AuthToken authPart) throws Exception {
        //BEGIN complete_job
		js.completeJob(authPart.getUserName(), job, getServiceName(token),
				status, error, unmakeResults(res));
        //END complete_job
    }

    /**
     * <p>Original spec-file function name: get_results</p>
     * <pre>
     * Get the job results.
     * </pre>
     * @param   job   instance of original type "job_id" (A job id.)
     * @return   parameter "res" of type {@link us.kbase.userandjobstate.Results Results}
     */
    @JsonServerMethod(rpc = "UserAndJobState.get_results")
    public Results getResults(String job, AuthToken authPart) throws Exception {
        Results returnVal = null;
        //BEGIN get_results
		returnVal = makeResults(getJobState(job, authPart).getJob(
				authPart.getUserName(), job).getResults());
        //END get_results
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_detailed_error</p>
     * <pre>
     * Get the detailed error message, if any
     * </pre>
     * @param   job   instance of original type "job_id" (A job id.)
     * @return   parameter "error" of original type "detailed_err" (Detailed information about a job error, such as a stacktrace, that will not fit in the job_status. No more than 100K characters.)
     */
    @JsonServerMethod(rpc = "UserAndJobState.get_detailed_error")
    public String getDetailedError(String job, AuthToken authPart) throws Exception {
        String returnVal = null;
        //BEGIN get_detailed_error
		returnVal =  getJobState(job, authPart).getJob(
				authPart.getUserName(), job).getErrorMsg();
        //END get_detailed_error
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_job_info</p>
     * <pre>
     * Get information about a job.
     * </pre>
     * @param   job   instance of original type "job_id" (A job id.)
     * @return   parameter "info" of original type "job_info" (Information about a job.) &rarr; tuple of size 14: parameter "job" of original type "job_id" (A job id.), parameter "service" of original type "service_name" (A service name. Alphanumerics and the underscore are allowed.), parameter "stage" of original type "job_stage" (A string that describes the stage of processing of the job. One of 'created', 'started', 'completed', or 'error'.), parameter "started" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time)), parameter "status" of original type "job_status" (A job status string supplied by the reporting service. No more than 200 characters.), parameter "last_update" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time)), parameter "prog" of original type "total_progress" (The total progress of a job.), parameter "max" of original type "max_progress" (The maximum possible progress of a job.), parameter "ptype" of original type "progress_type" (The type of progress that is being tracked. One of: 'none' - no numerical progress tracking 'task' - Task based tracking, e.g. 3/24 'percent' - percentage based tracking, e.g. 5/100%), parameter "est_complete" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time)), parameter "complete" of original type "boolean" (A boolean. 0 = false, other = true.), parameter "error" of original type "boolean" (A boolean. 0 = false, other = true.), parameter "desc" of original type "job_description" (A job description string supplied by the reporting service. No more than 1000 characters.), parameter "res" of type {@link us.kbase.userandjobstate.Results Results}
     */
    @JsonServerMethod(rpc = "UserAndJobState.get_job_info")
    public Tuple14<String, String, String, String, String, String, Long, Long, String, String, Long, Long, String, Results> getJobInfo(String job, AuthToken authPart) throws Exception {
        Tuple14<String, String, String, String, String, String, Long, Long, String, String, Long, Long, String, Results> returnVal = null;
        //BEGIN get_job_info
		returnVal = jobToJobInfo(getJobState(job, authPart).getJob(
				authPart.getUserName(), job));
		//TODO add job source to info and description? maybe not, backwards incompatible
        //END get_job_info
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: list_jobs</p>
     * <pre>
     * List jobs. Leave 'services' empty or null to list jobs from all
     * services.
     * </pre>
     * @param   services   instance of list of original type "service_name" (A service name. Alphanumerics and the underscore are allowed.)
     * @param   filter   instance of original type "job_filter" (A string-based filter for listing jobs. If the string contains: 'R' - running jobs are returned. 'C' - completed jobs are returned. 'E' - jobs that errored out are returned. 'S' - shared jobs are returned. The string can contain any combination of these codes in any order. If the string contains none of the codes or is null, all self-owned jobs that have been started are returned. If only the S filter is present, all jobs that have been started are returned.)
     * @return   parameter "jobs" of list of original type "job_info" (Information about a job.) &rarr; tuple of size 14: parameter "job" of original type "job_id" (A job id.), parameter "service" of original type "service_name" (A service name. Alphanumerics and the underscore are allowed.), parameter "stage" of original type "job_stage" (A string that describes the stage of processing of the job. One of 'created', 'started', 'completed', or 'error'.), parameter "started" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time)), parameter "status" of original type "job_status" (A job status string supplied by the reporting service. No more than 200 characters.), parameter "last_update" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time)), parameter "prog" of original type "total_progress" (The total progress of a job.), parameter "max" of original type "max_progress" (The maximum possible progress of a job.), parameter "ptype" of original type "progress_type" (The type of progress that is being tracked. One of: 'none' - no numerical progress tracking 'task' - Task based tracking, e.g. 3/24 'percent' - percentage based tracking, e.g. 5/100%), parameter "est_complete" of original type "timestamp" (A time in the format YYYY-MM-DDThh:mm:ssZ, where Z is the difference in time to UTC in the format +/-HHMM, eg: 2012-12-17T23:24:06-0500 (EST time) 2013-04-03T08:56:32+0000 (UTC time)), parameter "complete" of original type "boolean" (A boolean. 0 = false, other = true.), parameter "error" of original type "boolean" (A boolean. 0 = false, other = true.), parameter "desc" of original type "job_description" (A job description string supplied by the reporting service. No more than 1000 characters.), parameter "res" of type {@link us.kbase.userandjobstate.Results Results}
     */
    @JsonServerMethod(rpc = "UserAndJobState.list_jobs")
    public List<Tuple14<String, String, String, String, String, String, Long, Long, String, String, Long, Long, String, Results>> listJobs(List<String> services, String filter, AuthToken authPart) throws Exception {
        List<Tuple14<String, String, String, String, String, String, Long, Long, String, String, Long, Long, String, Results>> returnVal = null;
        //BEGIN list_jobs
		boolean queued = false;
		boolean running = false;
		boolean complete = false;
		boolean error = false;
		boolean shared = false;
		if (filter != null) {
			//TODO test with AWE
			if (filter.indexOf("Q") > -1) {
				queued = true;
			}
			if (filter.indexOf("R") > -1) {
				running = true;
			}
			if (filter.indexOf("C") > -1) {
				complete = true;
			}
			if (filter.indexOf("E") > -1) {
				error = true;
			}
			if (filter.indexOf("S") > -1) {
				shared = true;
			}
		}
		returnVal = new LinkedList<Tuple14<String, String, String, String,
				String, String, Long, Long, String, String, Long,
				Long, String, Results>>();
		List<JobState> jobstatus = new LinkedList<JobState>();
		jobstatus.add(js);
		if (aweUrl != null) {
			jobstatus.add(getAweJobState(authPart));
		}
		for (final JobState jobst: jobstatus) {
			for (final Job j: jobst.listJobs(authPart.getUserName(), services,
					queued, running, complete, error, shared)) {
				returnVal.add(jobToJobInfo(j));
			}
		}
        //END list_jobs
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: list_job_services</p>
     * <pre>
     * List all job services.
     * </pre>
     * @return   parameter "services" of list of original type "service_name" (A service name. Alphanumerics and the underscore are allowed.)
     */
    @JsonServerMethod(rpc = "UserAndJobState.list_job_services")
    public List<String> listJobServices(AuthToken authPart) throws Exception {
        List<String> returnVal = null;
        //BEGIN list_job_services
		returnVal = new ArrayList<String>(js.listServices(
				authPart.getUserName()));
		//TODO list awe services
        //END list_job_services
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: share_job</p>
     * <pre>
     * Share a job. Sharing a job to the same user twice or with the job owner
     * has no effect.
     * </pre>
     * @param   job   instance of original type "job_id" (A job id.)
     * @param   users   instance of list of original type "username" (Login name of a KBase user account.)
     */
    @JsonServerMethod(rpc = "UserAndJobState.share_job")
    public void shareJob(String job, List<String> users, AuthToken authPart) throws Exception {
        //BEGIN share_job
		checkUsers(users, authPart);
		//TODO 1 WAIT share awe jobs
		js.shareJob(authPart.getUserName(), job, users);
        //END share_job
    }

    /**
     * <p>Original spec-file function name: unshare_job</p>
     * <pre>
     * Stop sharing a job. Removing sharing from a user that the job is not
     * shared with or the job owner has no effect.
     * </pre>
     * @param   job   instance of original type "job_id" (A job id.)
     * @param   users   instance of list of original type "username" (Login name of a KBase user account.)
     */
    @JsonServerMethod(rpc = "UserAndJobState.unshare_job")
    public void unshareJob(String job, List<String> users, AuthToken authPart) throws Exception {
        //BEGIN unshare_job
		checkUsers(users, authPart);
		//TODO 1 WAIT unshare awe jobs
		js.unshareJob(authPart.getUserName(), job, users);
        //END unshare_job
    }

    /**
     * <p>Original spec-file function name: get_job_owner</p>
     * <pre>
     * Get the owner of a job.
     * </pre>
     * @param   job   instance of original type "job_id" (A job id.)
     * @return   parameter "owner" of original type "username" (Login name of a KBase user account.)
     */
    @JsonServerMethod(rpc = "UserAndJobState.get_job_owner")
    public String getJobOwner(String job, AuthToken authPart) throws Exception {
        String returnVal = null;
        //BEGIN get_job_owner
		returnVal = getJobState(job, authPart).getJob(
				authPart.getUserName(), job).getUser();
        //END get_job_owner
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: get_job_shared</p>
     * <pre>
     * Get the list of users with which a job is shared. Only the job owner
     * may access this method.
     * </pre>
     * @param   job   instance of original type "job_id" (A job id.)
     * @return   parameter "users" of list of original type "username" (Login name of a KBase user account.)
     */
    @JsonServerMethod(rpc = "UserAndJobState.get_job_shared")
    public List<String> getJobShared(String job, AuthToken authPart) throws Exception {
        List<String> returnVal = null;
        //BEGIN get_job_shared
		final Job j = getJobState(job, authPart).getJob(
				authPart.getUserName(), job);
		if (!j.getUser().equals(authPart.getUserName())) {
			throw new IllegalArgumentException(String.format(
					"User %s may not access the sharing list of job %s",
					authPart.getUserName(), job));
		}
		returnVal = j.getShared();
        //END get_job_shared
        return returnVal;
    }

    /**
     * <p>Original spec-file function name: delete_job</p>
     * <pre>
     * Delete a job. Will fail if the job is not complete.
     * </pre>
     * @param   job   instance of original type "job_id" (A job id.)
     */
    @JsonServerMethod(rpc = "UserAndJobState.delete_job")
    public void deleteJob(String job, AuthToken authPart) throws Exception {
        //BEGIN delete_job
		getJobState(job, authPart).deleteJob(
				authPart.getUserName(), job);
        //END delete_job
    }

    /**
     * <p>Original spec-file function name: force_delete_job</p>
     * <pre>
     * Force delete a job - will succeed unless the job has not been started.
     * In that case, the service must start the job and then delete it, since
     * a job is not "owned" by any service until it is started.
     * </pre>
     * @param   token   instance of original type "service_token" (A globus ID token that validates that the service really is said service.)
     * @param   job   instance of original type "job_id" (A job id.)
     */
    @JsonServerMethod(rpc = "UserAndJobState.force_delete_job")
    public void forceDeleteJob(String token, String job, AuthToken authPart) throws Exception {
        //BEGIN force_delete_job
		getJobState(job, authPart).deleteJob(authPart.getUserName(), job,
				getServiceName(token));
        //END force_delete_job
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("Usage: <program> <server_port>");
            return;
        }
        new UserAndJobStateServer().startupServer(Integer.parseInt(args[0]));
    }
}
