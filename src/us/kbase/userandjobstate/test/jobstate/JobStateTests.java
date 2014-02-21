package us.kbase.userandjobstate.test.jobstate;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bson.types.ObjectId;
import org.junit.BeforeClass;
import org.junit.Test;

import us.kbase.common.test.RegexMatcher;
import us.kbase.userandjobstate.jobstate.Job;
import us.kbase.userandjobstate.jobstate.JobState;
import us.kbase.userandjobstate.jobstate.exceptions.NoSuchJobException;
import us.kbase.userandjobstate.test.FakeJob;
import us.kbase.userandjobstate.test.UserJobStateTestCommon;

public class JobStateTests {
	
	private static JobState js;
	
	@BeforeClass
	public static void setUpClass() throws Exception {
		UserJobStateTestCommon.destroyAndSetupDB();
		
		String host = UserJobStateTestCommon.getHost();
		String mUser = UserJobStateTestCommon.getMongoUser();
		String mPwd = UserJobStateTestCommon.getMongoPwd();
		String db = UserJobStateTestCommon.getDB();
		
		if (mUser != null) {
			js = new JobState(host, db, "jobstate", mUser, mPwd);
		} else {
			js = new JobState(host, db, "jobstate");
		}
	}
	
	private static final RegexMatcher OBJ_ID_MATCH = new RegexMatcher("[\\da-f]{24}");
	
	private static final Date MAX_DATE = new Date(Long.MAX_VALUE);
	
	private static String long101;
	private static String long201;
	private static String long1001;
	private static String long100001;
	static {
		long101 = "";
		long201 = "";
		long1001 = "";
		long100001 = "";
		for (int i = 0; i < 5; i++) {
			long101 += "aaaaaaaaaabbbbbbbbbb";
		}
		long201 = long101 + long101;
		for (int i = 0; i < 5; i++) {
			long1001 += long201;
		}
		for (int i = 0; i < 100; i++) {
			long100001 += long1001;
		}
		long101 += "f";
		long201 += "f";
		long1001 += "f";
		long100001 += "f";
	}
	
	@Test
	public void createJob() throws Exception {
		try {
			js.createJob(null);
			fail("created job with invalid user");
		} catch (IllegalArgumentException iae) {
			assertThat("correct exception", iae.getLocalizedMessage(), 
					is("user cannot be null or the empty string"));
		}
		try {
			js.createJob("");
			fail("created job with invalid user");
		} catch (IllegalArgumentException iae) {
			assertThat("correct exception", iae.getLocalizedMessage(), 
					is("user cannot be null or the empty string"));
		}
		String jobid = js.createJob("foo");
		assertThat("get job id", jobid, OBJ_ID_MATCH);
		Job j = js.getJob("foo", jobid);
		checkJob(j, jobid, "created", null, "foo", null, null, null, null,
				null, null, null, null, null, null);
		try {
			js.getJob("foo1", jobid);
			fail("Got a non-existant job");
		} catch (NoSuchJobException nsje) {
			assertThat("correct exception", nsje.getLocalizedMessage(),
					is(String.format("There is no job %s viewable by user foo1", jobid)));
		}
		try {
			js.getJob("foo", "a" + jobid.substring(1));
			fail("Got a non-existant job");
		} catch (NoSuchJobException nsje) {
			assertThat("correct exception", nsje.getLocalizedMessage(),
					is(String.format("There is no job %s viewable by user foo",
					"a" + jobid.substring(1))));
		}
		try {
			js.getJob("foo", "a" + jobid);
			fail("Got a job with a bad id");
		} catch (IllegalArgumentException iae) {
			assertThat("correct exception", iae.getLocalizedMessage(),
					is(String.format("Job ID %s is not a legal ID",
					"a" + jobid)));
		}
	}
	
	@Test
	public void startJob() throws Exception {
		Date nearfuture = new Date(new Date().getTime() + 10000);
		Date nearpast = new Date(new Date().getTime() - 10);
		String jobid = js.createJob("foo");
		js.startJob("foo", jobid, "serv1", "started job", "job desc", null);
		Job j = js.getJob("foo", jobid);
		checkJob(j, jobid, "started", null, "foo", "started job", "serv1",
				"job desc", "none", null, null, false, false, null, null);
		testStartJobBadArgs("foo", jobid, "serv2", "started job", "job desc", null,
				new NoSuchJobException(String.format(
						"There is no unstarted job %s for user foo", jobid)));
		testStartJobBadArgs("foo1", jobid, "serv2", "started job", "job desc", null,
				new NoSuchJobException(String.format(
						"There is no unstarted job %s for user foo1", jobid)));
		testStartJobBadArgs("foo", "a" + jobid.substring(1), "serv2", "started job", "job desc", null,
				new NoSuchJobException(String.format(
						"There is no unstarted job %s for user foo", "a" + jobid.substring(1))));
		testStartJobBadArgs(null, jobid, "serv2", "started job", "job desc", null,
				new IllegalArgumentException("user cannot be null or the empty string"));
		testStartJobBadArgs("", jobid, "serv2", "started job", "job desc", null,
				new IllegalArgumentException("user cannot be null or the empty string"));
		testStartJobBadArgs(long101, jobid, "serv2", "started job", "job desc", null,
				new IllegalArgumentException("user exceeds the maximum length of 100"));
		testStartJobBadArgs("foo",  null, "serv2", "started job", "job desc", null,
				new IllegalArgumentException("id cannot be null or the empty string"));
		testStartJobBadArgs("foo", "", "serv2", "started job", "job desc", null,
				new IllegalArgumentException("id cannot be null or the empty string"));
		testStartJobBadArgs("foo", "afeaefafaefaefafeaf", "serv2", "started job", "job desc", null,
				new IllegalArgumentException("Job ID afeaefafaefaefafeaf is not a legal ID"));
		testStartJobBadArgs("foo", jobid, null, "started job", "job desc", null,
				new IllegalArgumentException("service cannot be null or the empty string"));
		testStartJobBadArgs("foo", jobid, "", "started job", "job desc", null,
				new IllegalArgumentException("service cannot be null or the empty string"));
		testStartJobBadArgs("foo", jobid, long101, "started job", "job desc", null,
				new IllegalArgumentException("service exceeds the maximum length of 100"));
		testStartJobBadArgs("foo", jobid, "serv2", long201, "job desc", null,
				new IllegalArgumentException("status exceeds the maximum length of 200"));
		testStartJobBadArgs("foo", jobid, "serv2", "started job", long1001, null,
				new IllegalArgumentException("description exceeds the maximum length of 1000"));
		testStartJobBadArgs("foo", jobid, "serv2", "started job", "job desc", nearpast,
				new IllegalArgumentException("The estimated completion date must be in the future"));
		try {
			js.startJob("foo", jobid, "serv2", "started job", "job desc", 0,
					null);
			fail("Started job with 0 for num tasks");
		} catch (IllegalArgumentException iae) {
			assertThat("correct exception", iae.getLocalizedMessage(),
					is("The maximum progress for the job must be > 0"));
		}
		int[] char1 = {11614};
		String uni = new String(char1, 0, 1);
		jobid = js.createJob("unicode");
		js.startJob("unicode", jobid, "serv3", uni, "desc3", 200,
				nearfuture);
		j = js.getJob("unicode", jobid);
		checkJob(j, jobid, "started", nearfuture, "unicode", uni,
				"serv3", "desc3", "task", 0, 200, false, false, null, null);
		
		jobid = js.createJob("foo3");
		js.startJob("foo3", jobid, "serv3", "start3", "desc3", 200, nearfuture);
		j = js.getJob("foo3", jobid);
		checkJob(j, jobid, "started", nearfuture, "foo3", "start3", "serv3",
				"desc3", "task", 0, 200, false, false, null, null);
		jobid = js.createJob("foo4");
		js.startJobWithPercentProg("foo4", jobid, "serv4", "start4", "desc4",
				nearfuture);
		j = js.getJob("foo4", jobid);
		checkJob(j, jobid, "started", nearfuture, "foo4", "start4", "serv4",
				"desc4", "percent", 0, 100, false, false, null, null);
		
		jobid = js.createAndStartJob("fooc1", "servc1", "startc1", "desc_c1",
				nearfuture);
		j = js.getJob("fooc1", jobid);
		checkJob(j, jobid, "started", nearfuture, "fooc1", "startc1",
				"servc1", "desc_c1", "none", null, null, false, false, null,
				null);
		
		jobid = js.createAndStartJob("fooc2", "servc2", "startc2", "desc_c2",
				50, nearfuture);
		j = js.getJob("fooc2", jobid);
		checkJob(j, jobid, "started", nearfuture, "fooc2", "startc2", "servc2",
				"desc_c2", "task", 0, 50, false, false, null, null);
		
		jobid = js.createAndStartJobWithPercentProg("fooc3", "servc3",
				"startc3", "desc_c3", nearfuture);
		j = js.getJob("fooc3", jobid);
		checkJob(j, jobid, "started", nearfuture, "fooc3", "startc3", "servc3",
				"desc_c3", "percent", 0, 100, false, false, null, null);
		
	}
	
	private void testStartJobBadArgs(String user, String jobid, String service,
			String status, String desc, Date estCompl, Exception exception)
			throws Exception {
		try {
			js.startJob(user, jobid, service, status, desc, estCompl);
			fail("Started job with bad args");
		} catch (Exception e) {
			assertThat("correct exception type", e,
					is(exception.getClass()));
			assertThat("correct exception msg", e.getLocalizedMessage(),
					is(exception.getLocalizedMessage()));
		}
		try {
			js.startJobWithPercentProg(user, jobid, service, status, desc,
					estCompl);
			fail("Started job with bad args");
		} catch (Exception e) {
			assertThat("correct exception type", e,
					is(exception.getClass()));
			assertThat("correct exception msg", e.getLocalizedMessage(),
					is(exception.getLocalizedMessage()));
		}
		try {
			js.startJob(user, jobid, service, status, desc, 6, estCompl);
			fail("Started job with bad args");
		} catch (Exception e) {
			assertThat("correct exception type", e,
					is(exception.getClass()));
			assertThat("correct exception msg", e.getLocalizedMessage(),
					is(exception.getLocalizedMessage()));
		}
		if (!goodID(jobid) || exception instanceof NoSuchJobException) {
			return;
		}
		try {
			js.createAndStartJob(user, service, status, desc, estCompl);
			fail("Started job with bad args");
		} catch (Exception e) {
			assertThat("correct exception type", e,
					is(exception.getClass()));
			assertThat("correct exception msg", e.getLocalizedMessage(),
					is(exception.getLocalizedMessage()));
		}
		try {
			js.createAndStartJobWithPercentProg(user, service, status, desc,
					estCompl);
			fail("Started job with bad args");
		} catch (Exception e) {
			assertThat("correct exception type", e,
					is(exception.getClass()));
			assertThat("correct exception msg", e.getLocalizedMessage(),
					is(exception.getLocalizedMessage()));
		}
		try {
			js.createAndStartJob(user, service, status, desc, 6, estCompl);
			fail("Started job with bad args");
		} catch (Exception e) {
			assertThat("correct exception type", e,
					is(exception.getClass()));
			assertThat("correct exception msg", e.getLocalizedMessage(),
					is(exception.getLocalizedMessage()));
		}
	}
	
	private static boolean goodID(String jobid) {
		try {
			new ObjectId(jobid);
			return true;
		} catch (IllegalArgumentException iae) {
			return false;
		}
	}
	
	private void checkJob(Job j, String id, String stage, Date estComplete, 
			String user, String status, String service, String desc,
			String progtype, Integer prog, Integer maxproj, Boolean complete,
			Boolean error, String errmsg, Map<String, Object> results) {
		assertThat("job id ok", j.getID(), is(id));
		assertThat("job stage ok", j.getStage(), is(stage));
		assertThat("job user ok", j.getUser(), is(user));
		assertThat("job service ok", j.getService(), is(service));
		assertThat("job desc ok", j.getDescription(), is(desc));
		assertThat("job progtype ok", j.getProgType(), is(progtype));
		assertThat("job prog ok", j.getProgress(), is(prog));
		assertThat("job maxprog ok", j.getMaxProgress(), is(maxproj));
		assertThat("job status ok", j.getStatus(), is(status));
		assertTrue("job started is ok", j.getStarted() == null ||
				j.getStarted() instanceof Date);
		assertThat("job est complete ok", j.getEstimatedCompletion(),
				is(estComplete));
		assertThat("job updated ok", j.getLastUpdated(), is(Date.class));
		assertThat("job complete ok", j.isComplete(), is(complete));
		assertThat("job error ok", j.hasError(), is(error));
		assertThat("job results ok", j.getResults(), is(results));
		assertThat("job error ok", j.getErrorMsg(), is(errmsg));
	}
	
	@Test
	public void updateJob() throws Exception {
		Date nearfuture = new Date(new Date().getTime() + 10000);
		Date nearpast = new Date(new Date().getTime() - 10);
		//task based progress
		String jobid = js.createAndStartJob("bar", "service1", "st", "de", 33,
				null);
		Job j = js.getJob("bar", jobid);
		checkJob(j, jobid, "started", null, "bar", "st", "service1", "de",
				"task", 0, 33, false, false, null, null);
		
		js.updateJob("bar", jobid, "service1", "new st", 4, null);
		j = js.getJob("bar", jobid);
		checkJob(j, jobid, "started", null, "bar", "new st",
				"service1", "de", "task", 4, 33, false, false, null, null);
		
		js.updateJob("bar", jobid, "service1", "new st2", 16, nearfuture);
		j = js.getJob("bar", jobid);
		checkJob(j, jobid, "started", nearfuture, "bar", "new st2",
				"service1", "de", "task", 20, 33, false, false, null, null);
		
		js.updateJob("bar", jobid, "service1", "this really should be done",
				16, null);
		j = js.getJob("bar", jobid);
		checkJob(j, jobid, "started", nearfuture, "bar",
				"this really should be done", "service1", "de", "task", 33, 33,
				false, false, null, null);
		
		//no progress tracking
		jobid = js.createAndStartJob("bar2", "service2", "st2", "de2", null);
		j = js.getJob("bar2", jobid);
		checkJob(j, jobid, "started", null, "bar2", "st2", "service2", "de2",
				"none", null, null, false, false, null, null);
		
		js.updateJob("bar2", jobid, "service2", "st2-2", null, nearfuture);
		j = js.getJob("bar2", jobid);
		checkJob(j, jobid, "started", nearfuture, "bar2", "st2-2", "service2",
				"de2", "none", null, null, false, false, null, null);
		
		js.updateJob("bar2", jobid, "service2", "st2-3", 6, null);
		j = js.getJob("bar2", jobid);
		checkJob(j, jobid, "started", nearfuture, "bar2", "st2-3", "service2",
				"de2", "none", null, null, false, false, null, null);
		
		//percentage based tracking
		jobid = js.createAndStartJobWithPercentProg("bar3", "service3", "st3",
				"de3", null);
		j = js.getJob("bar3", jobid);
		checkJob(j, jobid, "started", null, "bar3", "st3", "service3", "de3",
				"percent", 0, 100, false, false, null, null);
		
		js.updateJob("bar3", jobid, "service3", "st3-2", 30, null);
		j = js.getJob("bar3", jobid);
		checkJob(j, jobid, "started", null, "bar3", "st3-2", "service3", "de3",
				"percent", 30, 100, false, false, null, null);
		
		js.updateJob("bar3", jobid, "service3", "st3-3", 2, nearfuture);
		j = js.getJob("bar3", jobid);
		checkJob(j, jobid, "started", nearfuture, "bar3", "st3-3",
				"service3", "de3", "percent", 32, 100, false, false, null,
				null);
		
		js.updateJob("bar3", jobid, "service3", "st3-4", 80, null);
		j = js.getJob("bar3", jobid);
		checkJob(j, jobid, "started", nearfuture, "bar3", "st3-4",
				"service3", "de3", "percent", 100, 100, false, false, null,
				null);
		
		testUpdateJobBadArgs("bar3", jobid, "service2", "stat", null, null,
				new NoSuchJobException(String.format(
						"There is no uncompleted job %s for user bar3 started by service service2",
						jobid)));
		testUpdateJobBadArgs("bar2", jobid, "service3", "stat", null, null,
				new NoSuchJobException(String.format(
						"There is no uncompleted job %s for user bar2 started by service service3",
						jobid)));
		testUpdateJobBadArgs("bar2", "a" + jobid.substring(1), "service2", "stat", null, null,
				new NoSuchJobException(String.format(
						"There is no uncompleted job %s for user bar2 started by service service2",
						"a" + jobid.substring(1))));
		testUpdateJobBadArgs(null, jobid, "serv2", "started job", 1, null,
				new IllegalArgumentException("user cannot be null or the empty string"));
		testUpdateJobBadArgs("", jobid, "serv2", "started job", 1, null,
				new IllegalArgumentException("user cannot be null or the empty string"));
		testUpdateJobBadArgs(long101, jobid, "serv2", "started job", 1, null,
				new IllegalArgumentException("user exceeds the maximum length of 100"));
		testUpdateJobBadArgs("foo",  null, "serv2", "started job", 1, null,
				new IllegalArgumentException("id cannot be null or the empty string"));
		testUpdateJobBadArgs("foo", "", "serv2", "started job", 1, null,
				new IllegalArgumentException("id cannot be null or the empty string"));
		testUpdateJobBadArgs("foo", "afeaefafaefaefafeaf", "serv2", "started job", 1, null,
				new IllegalArgumentException("Job ID afeaefafaefaefafeaf is not a legal ID"));
		testUpdateJobBadArgs("foo", jobid, null, "started job", 1, null,
				new IllegalArgumentException("service cannot be null or the empty string"));
		testUpdateJobBadArgs("foo", jobid, "", "started job", 1, null,
				new IllegalArgumentException("service cannot be null or the empty string"));
		testUpdateJobBadArgs("foo", jobid, long101, "started job", 1, null,
				new IllegalArgumentException("service exceeds the maximum length of 100"));
		testUpdateJobBadArgs("foo", jobid, "serv2", long201, 1, null,
				new IllegalArgumentException("status exceeds the maximum length of 200"));
		testUpdateJobBadArgs("foo", jobid, "serv2", "started job", -1, null,
				new IllegalArgumentException("progress cannot be negative"));
		testUpdateJobBadArgs("foo", jobid, "serv2", "started job", -1, nearpast,
				new IllegalArgumentException("The estimated completion date must be in the future"));
		
		//fail on updating a completed or unstarted job
		jobid = js.createJob("foobar");
		testUpdateJobBadArgs("foobar", jobid, "serv2", "stat", 1, null,
				new NoSuchJobException(String.format(
				"There is no uncompleted job %s for user foobar started by service serv2",
				jobid)));
		jobid = js.createAndStartJob("foobar", "serv2", "stat", "desc", null);
		js.completeJob("foobar", jobid, "serv2", "stat", null, null);
		testUpdateJobBadArgs("foobar", jobid, "serv2", "stat", 1, null,
				new NoSuchJobException(String.format(
				"There is no uncompleted job %s for user foobar started by service serv2",
				jobid)));
	}
	
	private void testUpdateJobBadArgs(String user, String jobid, String service,
			String status, Integer progress, Date estCompl, Exception exception)
			throws Exception {
		try {
			js.updateJob(user, jobid, service, status, progress, estCompl);
			fail("updated job with bad args");
		} catch (Exception e) {
			assertThat("correct exception type", e,
					is(exception.getClass()));
			assertThat("correct exception msg", e.getLocalizedMessage(),
					is(exception.getLocalizedMessage()));
		}
	}
	
	@Test
	public void completeJob() throws Exception {
		String jobid = js.createAndStartJob("comp", "cserv1", "cstat1",
				"cdesc1", 5, null);
		js.updateJob("comp", jobid, "cserv1", "cstat1-2", 2, null);
		js.updateJob("comp", jobid, "cserv1", "cstat1-2", 6, null);
		Map<String, Object> res = new HashMap<String, Object>();
		res.put("shocknodes", Arrays.asList("node1", "node2"));
		js.completeJob("comp", jobid, "cserv1", "cstat1-3", "thing", res);
		Job j = js.getJob("comp", jobid);
		checkJob(j, jobid, "error", null, "comp", "cstat1-3", "cserv1", "cdesc1",
				"task", 5, 5, true, true, "thing", res);
		try {
			js.completeJob("comp", jobid, "cserv1", "cstat1-4", null, res);
			fail("completed a completed job");
		} catch (NoSuchJobException nsje) {
			assertThat("correct exception msg", nsje.getLocalizedMessage(),
					is(String.format(
					"There is no uncompleted job %s for user comp started by service cserv1",
					jobid)));
		}
		
		jobid = js.createAndStartJobWithPercentProg("comp", "cserv2", "cstat2",
				"cdesc2", null);
		js.updateJob("comp", jobid, "cserv2", "cstat2-2", 25, null);
		js.updateJob("comp", jobid, "cserv2", "cstat2-3", 50, null);
		js.completeJob("comp", jobid, "cserv2", "cstat2-3", null, res);
		j = js.getJob("comp", jobid);
		checkJob(j, jobid, "complete", null, "comp", "cstat2-3", "cserv2", "cdesc2",
				"percent", 100, 100, true, false, null, res);
		
		jobid = js.createJob("comp");
		try {
			js.completeJob("comp", jobid, "cserv2", "badstat", null, res);
			fail("completed an unstarted job");
		} catch (NoSuchJobException nsje) {
			assertThat("correct exception msg", nsje.getLocalizedMessage(),
					is(String.format(
					"There is no uncompleted job %s for user comp started by service cserv2",
					jobid)));
		}
		
		jobid = js.createAndStartJob("comp", "service2", "stat", "desc", null);
		
		testCompleteJobBadArgs("comp1", jobid, "service2", "stat", null,
				new NoSuchJobException(String.format(
						"There is no uncompleted job %s for user comp1 started by service service2",
						jobid)));
		testCompleteJobBadArgs("comp", jobid, "service3", "stat", null,
				new NoSuchJobException(String.format(
						"There is no uncompleted job %s for user comp started by service service3",
						jobid)));
		testCompleteJobBadArgs("comp", "a" + jobid.substring(1), "service2", "stat", null,
				new NoSuchJobException(String.format(
						"There is no uncompleted job %s for user comp started by service service2",
						"a" + jobid.substring(1))));
		testCompleteJobBadArgs(null, jobid, "service2", "started job", null,
				new IllegalArgumentException("user cannot be null or the empty string"));
		testCompleteJobBadArgs("", jobid, "service2", "started job", null,
				new IllegalArgumentException("user cannot be null or the empty string"));
		testCompleteJobBadArgs(long101, jobid, "service2", "started job", null,
				new IllegalArgumentException("user exceeds the maximum length of 100"));
		testCompleteJobBadArgs("comp",  null, "service2", "started job", null,
				new IllegalArgumentException("id cannot be null or the empty string"));
		testCompleteJobBadArgs("comp", "", "service2", "started job", null,
				new IllegalArgumentException("id cannot be null or the empty string"));
		testCompleteJobBadArgs("comp", "afeaefafaefaefafeaf", "service2", "started job", null,
				new IllegalArgumentException("Job ID afeaefafaefaefafeaf is not a legal ID"));
		testCompleteJobBadArgs("comp", jobid, null, "started job", null,
				new IllegalArgumentException("service cannot be null or the empty string"));
		testCompleteJobBadArgs("comp", jobid, "", "started job", null,
				new IllegalArgumentException("service cannot be null or the empty string"));
		testCompleteJobBadArgs("comp", jobid, long101, "started job", null,
				new IllegalArgumentException("service exceeds the maximum length of 100"));
		testCompleteJobBadArgs("comp", jobid, "service2", long201, null,
				new IllegalArgumentException("status exceeds the maximum length of 200"));
		testCompleteJobBadArgs("comp", jobid, "service2", "started job", long100001,
				new IllegalArgumentException("error exceeds the maximum length of 100000"));
	}
	
	private void testCompleteJobBadArgs(String user, String jobid, String service,
			String status, String errormsg, Exception exception) throws Exception {
		try {
			js.completeJob(user, jobid, service, status, errormsg, null);
			fail("completed job with bad args");
		} catch (Exception e) {
			assertThat("correct exception type", e,
					is(exception.getClass()));
			assertThat("correct exception msg", e.getLocalizedMessage(),
					is(exception.getLocalizedMessage()));
		}
	}
	
	@Test
	public void checkDateUpdates() throws Exception {
		String jobid = js.createJob("date");
		Job j = js.getJob("date", jobid);
		Date create = j.getLastUpdated();
		js.startJob("date", jobid, "serv1", "stat", "desc", null);
		j = js.getJob("date", jobid);
		Date start = j.getLastUpdated();
		js.updateJob("date", jobid, "serv1", "stat", null, null);
		j = js.getJob("date", jobid);
		Date update = j.getLastUpdated();
		js.updateJob("date", jobid, "serv1", "stat", null, null);
		j = js.getJob("date", jobid);
		Date update2 = j.getLastUpdated();
		js.completeJob("date", jobid, "serv1", "stat", null, null);
		j = js.getJob("date", jobid);
		Date complete = j.getLastUpdated();
		
		assertTrue("date created < started", create.compareTo(start) == -1);
		assertTrue("date started < updated", start.compareTo(update) == -1);
		assertTrue("date updated < updated2", update.compareTo(update2) == -1);
		assertTrue("date updated2 < complete", update2.compareTo(complete) == -1);
	}
	
	@Test
	public void deleteJob() throws Exception {
		String jobid = js.createAndStartJob("delete", "serv1", "st", "dsc",
				null);
		js.completeJob("delete", jobid, "serv1", "st", null, null);
		Job j = js.getJob("delete", jobid); //should work
		checkJob(j, jobid, "complete", null, "delete", "st", "serv1", "dsc",
				"none", null, null, true, false, null, null);
		succeedAtDeletingJob("delete", jobid);
		failToDeleteJob("delete", jobid, null);
		
		jobid = js.createJob("delete");
		failToDeleteJob("delete", jobid, null);
		js.startJob("delete", jobid, "s", "s", "d", null);
		failToDeleteJob("delete", jobid, null);
		js.updateJob("delete", jobid, "s", "s", 1, null);
		failToDeleteJob("delete", jobid, null);
		failToDeleteJob("delete1", jobid, null);
		failToDeleteJob("delete", "a" + jobid.substring(1), null);
		
		failToDeleteJob("delete1", jobid, "serv1");
		failToDeleteJob("delete", "a" + jobid.substring(1), "serv1");
		
		jobid = js.createJob("delete");
//		succeedAtDeletingJob("delete", jobid, "serv1");
		failToDeleteJob("delete", jobid, "serv1");
		jobid = js.createAndStartJob("delete", "serv1", "st", "dsc", null);
		succeedAtDeletingJob("delete", jobid, "serv1");
		failToDeleteJob("delete", jobid, "serv1");
		jobid = js.createAndStartJob("delete", "serv1", "st", "dsc", null);
		js.updateJob("delete", jobid, "serv1", "st", null, null);
		succeedAtDeletingJob("delete", jobid, "serv1");
		failToDeleteJob("delete", jobid, "serv1");
		jobid = js.createAndStartJob("delete", "serv1", "st", "dsc", null);
		js.completeJob("delete", jobid, "serv1", "st", null, null);
		succeedAtDeletingJob("delete", jobid, "serv1");
		failToDeleteJob("delete", jobid, "serv1");
		
	}
	
	private void succeedAtDeletingJob(String user, String jobid, String service)
			throws Exception {
		js.deleteJob(user, jobid, service);
		try {
			js.getJob(user, jobid);
			fail("got deleted job");
		} catch (NoSuchJobException nsje) {
			assertThat("correct exception msg", nsje.getLocalizedMessage(),
					is(String.format(
					"There is no job %s viewable by user %s", jobid, user)));
		}
	}
	
	private void succeedAtDeletingJob(String user, String jobid)
			throws Exception {
		js.deleteJob(user, jobid);
		try {
			js.getJob(user, jobid);
			fail("got deleted job");
		} catch (NoSuchJobException nsje) {
			assertThat("correct exception msg", nsje.getLocalizedMessage(),
					is(String.format(
					"There is no job %s viewable by user %s", jobid, user)));
		}
	}
	
	private void failToDeleteJob(String user, String jobid, String service)
			throws Exception {
		try {
			js.deleteJob(user, jobid, service);
			fail("deleted job when should've failed");
		} catch (NoSuchJobException nsje) {
			assertThat("correct exception msg", nsje.getLocalizedMessage(),
					is(String.format(
					"There is no %sjob %s for user %s",
					service == null ? "completed " : "", jobid, user +
					(service == null ? "" : " and service " + service))));
		}
		if (service != null) {
			return;
		}
		try {
			js.deleteJob(user, jobid);
			fail("deleted job when should've failed");
		} catch (NoSuchJobException nsje) {
			assertThat("correct exception msg", nsje.getLocalizedMessage(),
					is(String.format(
					"There is no completed job %s for user %s",
					jobid, user)));
		}
	}
	
	@Test
	public void listServices() throws Exception {
		checkListServ("listserv", new ArrayList<String>());
		String jobid = js.createJob("listserv");
		checkListServ("listserv", new ArrayList<String>());
		js.startJob("listserv", jobid, "serv1", null, null, null);
		checkListServ("listserv", Arrays.asList("serv1"));
		checkListServ("listserv2", new ArrayList<String>());
		js.createAndStartJob("listserv", "serv2", null, null, null);
		checkListServ("listserv", Arrays.asList("serv1", "serv2"));
		jobid = js.createAndStartJob("listserv2", "serv3", null, null, null);
		checkListServ("listserv", Arrays.asList("serv1", "serv2"));
		checkListServ("listserv2", Arrays.asList("serv3"));
		js.shareJob("listserv2", jobid, Arrays.asList("listserv"));
		checkListServ("listserv", Arrays.asList("serv1", "serv2", "serv3"));
		js.unshareJob("listserv2", jobid, Arrays.asList("listserv"));
		checkListServ("listserv", Arrays.asList("serv1", "serv2"));
	}
	
	private void checkListServ(String user, List<String> expected) throws Exception {
		Set<String> expc = new HashSet<String>(expected);
		assertThat("get correct services", js.listServices(user),
				is(expc));
	}
	
	@Test
	public void listJobs() throws Exception {
		String lj = "listjobs";
		List<FakeJob> empty = new ArrayList<FakeJob>();
		checkListJobs(empty, js.listJobs(lj, Arrays.asList("serv1"), true, true, true, false));
		String jobid = js.createJob(lj);
		checkListJobs(empty, js.listJobs(lj, Arrays.asList("serv1"), true, true, true, false));
		
		jobid = js.createAndStartJob(lj, "serv1", "lst", "ldsc", 42, MAX_DATE);
		FakeJob started = new FakeJob(jobid, lj, "serv1", "started",
				MAX_DATE,"ldsc", "task", 0, 42, "lst", false, false, null, null);
		checkListJobs(Arrays.asList(started), js.listJobs(lj, Arrays.asList("serv1"), true, true, true, false));
		checkListJobs(empty, js.listJobs(lj, Arrays.asList("serv2"), true, true, true, false));
		checkListJobs(Arrays.asList(started), js.listJobs(lj, Arrays.asList("serv1"), false, false, false, false));
		
		jobid = js.createAndStartJob(lj, "serv1", "comp-st", "comp-dsc",
				MAX_DATE);
		js.completeJob(lj, jobid, "serv1", "comp-st1", null, null);
		FakeJob complete = new FakeJob(jobid, lj, "serv1", "complete",
				MAX_DATE, "comp-dsc", "none", null, null, "comp-st1",
				true, false, null, null);
		
		jobid = js.createAndStartJobWithPercentProg(lj, "serv1", "err-st",
				"err-dsc", MAX_DATE);
		js.completeJob(lj, jobid, "serv1", "err-st1", "some error", null);
		FakeJob error = new FakeJob(jobid, lj, "serv1", "error",
				MAX_DATE, "err-dsc", "percent", 100, 100, "err-st1", true,
				true, "some error", null);
		
		//all 3
		List<FakeJob> all = Arrays.asList(started, complete, error);
		checkListJobs(all, js.listJobs(lj, Arrays.asList("serv1"), true, true, true, false));
		checkListJobs(all, js.listJobs(lj, Arrays.asList("serv1"), false, false, false, false));
		
		//1 of 3
		checkListJobs(Arrays.asList(started),
				js.listJobs(lj, Arrays.asList("serv1"), true, false, false, false));
		checkListJobs(Arrays.asList(complete),
				js.listJobs(lj, Arrays.asList("serv1"), false, true, false, false));
		checkListJobs(Arrays.asList(error),
				js.listJobs(lj, Arrays.asList("serv1"), false, false, true, false));
		
		//2 of 3
		checkListJobs(Arrays.asList(started, complete),
				js.listJobs(lj, Arrays.asList("serv1"), true, true, false, false));
		checkListJobs(Arrays.asList(complete, error),
				js.listJobs(lj, Arrays.asList("serv1"), false, true, true, false));
		checkListJobs(Arrays.asList(started, error),
				js.listJobs(lj, Arrays.asList("serv1"), true, false, true, false));
		
		//check on jobs from multiple services
		jobid = js.createAndStartJob(lj, "serv2", "mst", "mdsc", 42, MAX_DATE);
		FakeJob multi = new FakeJob(jobid, lj, "serv2", "started",
				MAX_DATE, "mdsc", "task", 0, 42, "mst", false, false, null, null);
		checkListJobs(Arrays.asList(started, complete, error, multi),
				js.listJobs(lj, new ArrayList<String>(), true, true, true, false));
		checkListJobs(Arrays.asList(started, complete, error, multi),
				js.listJobs(lj, null, true, true, true, false));
		checkListJobs(Arrays.asList(started, complete, error, multi),
				js.listJobs(lj, Arrays.asList("serv1", "serv2"), true, true, true, false));
		checkListJobs(Arrays.asList(started, complete),
				js.listJobs(lj, Arrays.asList("serv1"), true, true, false, false));
		checkListJobs(Arrays.asList(multi),
				js.listJobs(lj, Arrays.asList("serv2"), true, true, true, false));
		
		//check on shared jobs
		jobid = js.createAndStartJob("listJobsShare", "shareserv", "sst", "sdsc", null);
		FakeJob shared = new FakeJob(jobid, "listJobsShare", "shareserv", "started",
				null, "sdsc", "none", null, null, "sst", false, false, null, null);
		checkListJobs(Arrays.asList(started),
				js.listJobs(lj, Arrays.asList("serv1", "shareserv"), true, false, false, true));
		js.shareJob("listJobsShare", jobid, Arrays.asList(lj));
		checkListJobs(Arrays.asList(started),
				js.listJobs(lj, Arrays.asList("serv1", "shareserv"), true, false, false, false));
		checkListJobs(Arrays.asList(started, shared),
				js.listJobs(lj, Arrays.asList("serv1", "shareserv"), true, false, false, true));
		js.unshareJob("listJobsShare", jobid, Arrays.asList(lj));
		checkListJobs(Arrays.asList(started),
				js.listJobs(lj, Arrays.asList("serv1", "shareserv"), true, false, false, true));
		
	}
	
	private void checkListJobs(List<FakeJob> expected, List<Job> result)
		throws Exception {
		HashSet<FakeJob> res = new HashSet<FakeJob>();
		for (Job j: result) {
			res.add(new FakeJob(j));
		}
		assertThat("got expected jobs back", res, is(new HashSet<FakeJob>(expected)));
	}
	
	@Test
	public void shareJob() throws Exception {
		//TODO these are not tests, just trying things out
		String sh = "share";
		String jobid = js.createAndStartJob(sh, "shareserv", "st", "dsc",
				null);
		js.shareJob(sh, jobid, Arrays.asList("foo", "bar"));
		js.shareJob(sh, jobid, Arrays.asList("foo", "bar"));
		js.shareJob(sh, jobid, Arrays.asList("foo", "bar", "baz", "boop"));
		js.unshareJob("bar", jobid, Arrays.asList("bar"));
		js.unshareJob(sh, jobid, Arrays.asList("baz"));
		System.out.println(js.getJob("foo", jobid));
		System.out.println(js.listJobs("foo", null, true, true, true, true));
		System.out.println(js.listJobs("foo", null, true, true, true, false));
		System.out.println(js.listServices("foo"));
		
//		js.unshareJob("foo", jobid, Arrays.asList("boop")); //should fail
		
		
	}
}
