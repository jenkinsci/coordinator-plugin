package org.jenkinsci.plugins.coordinator.model;

import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.ItemGroup;
import hudson.model.ParameterValue;
import hudson.model.Queue.Executable;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Cause.UpstreamCause;
import hudson.model.CauseAction;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Run;
import hudson.model.queue.QueueTaskFuture;
import hudson.model.queue.ScheduleResult;
import hudson.model.queue.SubTask;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.ServletException;

import jenkins.model.Jenkins;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;

import org.acegisecurity.Authentication;
import org.acegisecurity.context.SecurityContextHolder;
import org.jenkinsci.plugins.coordinator.model.TreeNode.State;


/**
 * Helper class that avoid a lot of parameters go around all methods' signatures
 * and make this build process concurrent-able
 * @author Ace Han
 *
 */
public class PerformExecutor {
	
	// we need to explicitly declare that it has to be ConcurrentHashMap
	// <parentTreeNodeId, <childTreeNodeId, childTreeNode>>
	private ConcurrentHashMap<String, Map<String, TreeNode>> parentChildrenMap
								= new ConcurrentHashMap<String, Map<String, TreeNode>>();
	
	// for a cancelled execution polling indication <treeNodeId, activeBuild>
	// active means pending, building job
	private ConcurrentHashMap<String, AbstractBuild<?, ?>> activeBuildMap
								= new ConcurrentHashMap<String, AbstractBuild<?, ?>>();;
	
	protected ConcurrentHashMap<String, AbstractBuild<?, ?>> getActiveBuildMap() {
		return activeBuildMap;
	}

	private CoordinatorBuild coordinatorBuild;
	
	private BuildListener listener;
	
	// avoid a sudden peak thread creation in memory
	private ExecutorService executorPool;
	
	// for updating the buildNumber in CoordinatorParameterValue
	private Map<String, TreeNode> parameterMap;
	
	public PerformExecutor(CoordinatorBuild cb, BuildListener listener, int poolSize){
		this.coordinatorBuild = cb;
		cb.setPerformExecutor(this);
		this.listener = listener;
		executorPool = Executors.newFixedThreadPool(poolSize);
	}
	
	public boolean execute(){
		prepareExecutionPlan();
		kickOffBuild(this.coordinatorBuild.getOriginalExecutionPlan());
		while(!parentChildrenMap.isEmpty()){
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				// do nothing just stop the polling
				formattedLog("Unexpected Interruption:\n%s\n", e);
				return false;
			}
			// any shutdown means some unexpected exception happened during the progress
			if(executorPool.isShutdown()
					// wait for the already running tasks getting done 
					&& activeBuildMap.isEmpty()){
				return false;
			}
		}
		return true;
	}


	private void formattedLog(String format, Object ... args) {
		synchronized(listener){
			listener.getLogger().format(format, args);
		}
	}
	
	private void prepareExecutionPlan() {
		CoordinatorParameterValue parameter = (CoordinatorParameterValue)this.coordinatorBuild.getAction(ParametersAction.class)
				.getParameter(CoordinatorParameterValue.PARAM_KEY);

		TreeNode rootNode = null;
		if (parameter == null) {
			// use default execution plan when the job is scheduled
			rootNode = this.coordinatorBuild.getDefaultExecutionPlan();
		} else {
			rootNode = parameter.getValue();
		}

		TreeNode.mergeState(this.coordinatorBuild.getOriginalExecutionPlan(), rootNode);
		parameterMap = new HashMap<String, TreeNode>();
		for(TreeNode node: rootNode.getFlatNodes(false)){
			parameterMap.put(node.getId(), node);
		}
		
	}
	
	


	/*package*/ void kickOffBuild(TreeNode node){
		State state = node.getState();
		if(executorPool.isShutdown()
				|| state.disabled 
				|| !(state.checked || state.undetermined)){
			// patch-up for serial build node.getChildren().get(0) is a not checked/determined node
			postBuildParentChildrenMap(node);
			return;
		}
		if(node.isLeaf()){
			if(executorPool.isShutdown()){
				// no submit any new thread
				return;
			}
			Authentication auth = Jenkins.getAuthentication();
			executorPool.submit(new Execution(node, auth), node);
		} else if(node.shouldChildrenParallelRun()){
			prepareParentChildrenMap(node, true);
			for(TreeNode child: node.getChildren()){
				kickOffBuild(child);
			}
		} else {
			prepareParentChildrenMap(node, false);
			kickOffBuild(node.getChildren().get(0));
		}
	}


	ArrayList<Action> prepareJobActions(
			final AbstractProject<?, ?> atomicProject) {
		final ArrayList<Action> actions = new ArrayList<Action>(3);
		// make it only last cause visible instead of the entire series of causes  
		UpstreamCause upstreamCause = new UpstreamCause((Run<?, ?>)this.coordinatorBuild);
		CauseAction causeAction = new CauseAction(upstreamCause);
		
		actions.add(causeAction);
		ParametersAction paramsAction = prepareParametersAction(atomicProject);
		if(paramsAction != null){
			actions.add(paramsAction);
		}
		return actions;
	}


	protected void postBuildParentChildrenMap(TreeNode node) {
		TreeNode parent = node.getParent();
		if(parent == null){
			// means node == root => true
			// do nothing to wait for the polling in #perform() to end
			return;
		}
		Map<String, TreeNode> childMap = this.parentChildrenMap.get(parent.getId());
		childMap.remove(node.getId());
		
		if(parent.shouldChildrenSerialRun()){
			// since it's LinkedHashMap in the creation, the insertion order still resists
			Iterator<TreeNode> iter = childMap.values().iterator();
			if(iter.hasNext()){
				kickOffBuild(iter.next());	
			}
		}
		if(childMap.isEmpty()) {
			this.parentChildrenMap.remove(parent.getId());
			postBuildParentChildrenMap(parent);
		}
		
	}


	private void doPostBuildLog(final TreeNode node, Result result) {
		String jobName = node.getText();
		synchronized(listener){
			try {
				StringBuilder sb = new StringBuilder(100);
				// we use relative path
				sb.append("../../").append(jobName);
				listener.getLogger().print("Atomic Job: ");
				listener.hyperlink(sb.toString(), jobName);
				sb.append('/').append(node.getBuildNumber()).append("/console");
				listener.getLogger().print("  ");
				listener.hyperlink(sb.toString(), "#" + node.getBuildNumber());
				listener.getLogger().format(" Completed, Result: %s\n", result);
			} catch (IOException e) {
				// only change to log the message without hyper link
				listener.getLogger().format(
						"Item(%1$s) #%2$-6d Completed, Result: %3$s\n", jobName,
						node.getBuildNumber(), result);
			}
		}
	}
	

	private AbstractProject<?, ?> prepareProxiedProject(final TreeNode node) {
		AbstractProject<?, ?> atomicProject = (AbstractProject<?, ?>) Jenkins.getInstance().getItem(node.getText());
		if(atomicProject == null){
			formattedLog("Atomic Job: %s not found\n", node.getText());
			executorPool.shutdown();
			return null;
		}
		if(!atomicProject.isBuildable()){
			formattedLog("Atomic Job: %s is either disabled or new job's configuration not saved[refer to hudson.model.Job#isHoldOffBuildUntilSave]\n", 
					node.getText());
			executorPool.shutdown();
			return null;
		}
		Enhancer en = new Enhancer();
		en.setClassLoader(this.getClass().getClassLoader());
		en.setSuperclass(atomicProject.getClass());
		en.setCallback(new InjectedProjectProxy(atomicProject, node));
		atomicProject = (AbstractProject<?, ?>) en.create(new Class<?>[] {
				ItemGroup.class, String.class },
				new Object[] { atomicProject.getParent(), node.getText()});
		return atomicProject;
	}


	private ParametersAction prepareParametersAction(
			AbstractProject<?, ?> atomicProject) {
		ParametersDefinitionProperty atomicProjectPdp = atomicProject.getProperty(ParametersDefinitionProperty.class);
		if(atomicProjectPdp == null){
			return null;
		}
		List<ParameterDefinition> atomicProjectPds = atomicProjectPdp.getParameterDefinitions();
		List<ParameterValue> coordinatorBuildParameterValues = this.coordinatorBuild
										.getAction(ParametersAction.class)
										.getParameters();
		
		List<ParameterValue> values = new ArrayList<ParameterValue>();
		// the meat of casting parameters to downstream project
		for(ParameterDefinition pd: atomicProjectPds){
			boolean shouldAddDefault = true;
			ParameterValue defaultParameterValue = pd.getDefaultParameterValue();
			if(defaultParameterValue == null){
				// some ParameterDefinition's getDefaultParameterValue() returns a null
				formattedLog(
						"Atomic Job( %s )'s  ParameterDefinition, ( %s ), is not supported and its parameter value won't get activated.\n",
						atomicProject.getName(), pd.getClass().getSimpleName());
				continue;
			}
			String defaultParamName = defaultParameterValue.getName();
			for(ParameterValue coordinatorBuildPv: coordinatorBuildParameterValues){
				if(defaultParameterValue.getClass() == coordinatorBuildPv.getClass() 
						&& defaultParamName.equals(coordinatorBuildPv.getName())){
					values.add(coordinatorBuildPv);
					shouldAddDefault = false;
					break;
				}
			}
			if(shouldAddDefault){
				values.add(defaultParameterValue);
			}
		}
		return new ParametersAction(values);
	}



	
	/*package*/ void prepareParentChildrenMap(TreeNode node,
			boolean shouldChildrenParallelRun) {
		List<TreeNode> children = node.getChildren();
		// maybe I got way too suspicious
//		if(children.isEmpty()){
//			// should be not empty
//			return;
//		}
		int capacity = Math.max((int) (children.size()/.75f) + 1, 16);
		Map<String, TreeNode> idNodeMap = shouldChildrenParallelRun
											? new ConcurrentHashMap<String, TreeNode>(capacity)
											// we need the insertion order later on
											: new LinkedHashMap<String, TreeNode>(capacity);
		
		for(TreeNode child: children){
			child.setParent(node);
			State state = child.getState();
			if(!state.disabled && (state.checked || state.undetermined)){
				idNodeMap.put(child.getId(), child);
			}
		}
		parentChildrenMap.put(node.getId(), idNodeMap);
	}
	
	public void shutdown() throws IOException, ServletException {
		if(!executorPool.isShutdown()){
			// stop the running children within
			executorPool.shutdown();
			for(AbstractBuild<?, ?> ab: activeBuildMap.values()){
				ab.doStop();
			}
			// ensure a exit condition in PerformExecutor#execute
			activeBuildMap.clear();
		}
	}
	
	public void shutdownQuietly(){
		try {
			shutdown();
		} catch (Exception e) {
			// ensure a exit condition in PerformExecutor#execute
			activeBuildMap.clear();
			formattedLog("shutdownQuietly failed with %s", e);
		}
	}
	
	/**
	 * just for displaying the build # in the CoordinatorParameterValue
	 * @author Ace Han
	 *
	 */
	/*package*/ class InjectedProjectProxy implements MethodInterceptor {

		private AbstractProject<?, ?> originalAtomicProject;
		private TreeNode node;
		
		/*package*/ InjectedProjectProxy(AbstractProject<?, ?> project, TreeNode node) {
			this.originalAtomicProject = project;
			this.node = node;
		}

		@Override
		public Object intercept(Object obj, Method method, Object[] args,
				MethodProxy proxy) throws Throwable {
			method.setAccessible(true);
			Object result = method.invoke(originalAtomicProject, args);
			if (method.getName().equals("getSubTasks")){
				// when it's actually in build process, 
				// may need to replace the the original one to make the proxied one get execution
				// in order to make sure our build # retrieval
				@SuppressWarnings("unchecked")
				List<SubTask> subTasks = (List<SubTask>) result;
				if(subTasks.contains(originalAtomicProject)){
					int index = subTasks.indexOf(originalAtomicProject);
					subTasks.add(index, (AbstractProject<?, ?>)obj);
					subTasks.remove(index + 1);
				}
			} else if (method.getName().equals("createExecutable")) {
				AbstractBuild<?, ?> ab = (AbstractBuild<?, ?>) result;
				this.node.setBuildNumber(ab.getNumber());
				
				// get the buildNumber in CoordinatorParameterValue updated respectively
				PerformExecutor.this.parameterMap.get(node.getId()).setBuildNumber(ab.getNumber());
				
				if(PerformExecutor.this.executorPool.isShutdown()){
					// the coordinator build already marked shutdown
					ab.doStop();
				} else {
					PerformExecutor.this.activeBuildMap.put(node.getId(), ab);
				}
			}
			return result;
		}
		
	}
	
	/*package*/ class Execution implements Runnable{
		
		private TreeNode node;
		private Authentication auth;
		
		Execution(TreeNode node, Authentication auth){
			this.node = node;
			this.auth = auth;
		}
		
		@Override
		public void run() {
			String jobName = node.getText();
			AbstractProject<?, ?> atomicProject = prepareProxiedProject(node);
			if(atomicProject == null) return;
			List<Action> actions = prepareJobActions(atomicProject);
			formattedLog("Atomic Job ( %s ) Triggered\n", jobName);
			QueueTaskFuture<Executable> future = getRunningFuture(node, atomicProject, actions);
			AbstractBuild<?, ?> targetBuild;
			if(future == null) return;
			// TODO should make it configurable
			int time = 8;
			try {
				future.get(time, TimeUnit.HOURS);
			} catch (TimeoutException e) {
				formattedLog("Atomic Job(%1$s-%2$s) Time out, waited for %3$d %s, Exception:\n%4$s\n", 
						node.getId(), jobName, time, TimeUnit.HOURS, e);
				PerformExecutor.this.executorPool.shutdown();
			} catch (Exception e) {
				formattedLog("Atomic Job(%1$s-%2$s#%3$s) failed, Exception:\n%4$s\n",
						node.getId(), jobName, 
						node.getBuildNumber(),	// since this build# got filled in proxiedProject#newBuild
						e);
				PerformExecutor.this.executorPool.shutdown();
			} finally{
				// fixes #2841, Atomic Jobs without READ permission whilst Controller Job could be kicked off
				SecurityContextHolder.getContext().setAuthentication(auth);
				targetBuild = activeBuildMap.remove(node.getId());
			}
			if(targetBuild == null){
				return;
			}
			
			Result result = targetBuild.getResult();
			doPostBuildLog(node, result);
			if(result == Result.SUCCESS || result == Result.UNSTABLE){
				postBuildParentChildrenMap(node);
			} else {
				executorPool.shutdown();
			}
		}

		private QueueTaskFuture<Executable> getRunningFuture(TreeNode node,	
				AbstractProject<?, ?> atomicProject, List<Action> actions) {
			int rescheduleCount = 0;
			// TODO should be tested
			while(rescheduleCount ++ < 3){
				ScheduleResult result = Jenkins.getInstance().getQueue().schedule2(atomicProject, 0, actions);
				if(result.isRefused()){
					formattedLog("Jenkins refused to add Atomic Job ( %s ), considered as a failure, aborting entire coordinator job\n", 
								node.getText());
					// In this case, I take it as sth. wrong in Jenkins, a force shutdown to check it out
					shutdownQuietly();
				} else if (!result.isCreated()){
					// this means duplication in Jenkins Queue, 
					// maybe the same text repeats in the executionPlan in builder's config
					try {
						Thread.sleep(3000);
					} catch (InterruptedException e) {
						// did nothing and go to next round schedule2 directly
					}
				} else {
					return result.getItem().getFuture();
				}
			}
			return null;
		}
		
	// return this node after successful process for later reference	
	}
}
