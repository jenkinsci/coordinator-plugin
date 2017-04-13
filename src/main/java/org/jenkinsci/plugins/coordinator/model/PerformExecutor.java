package org.jenkinsci.plugins.coordinator.model;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.*;
import hudson.model.Cause.UpstreamCause;
import hudson.model.Queue.Executable;
import hudson.model.queue.QueueTaskFuture;
import hudson.model.queue.ScheduleResult;
import hudson.model.queue.SubTask;
import jenkins.model.Jenkins;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import org.acegisecurity.Authentication;
import org.acegisecurity.context.SecurityContextHolder;
import org.jenkinsci.plugins.coordinator.model.TreeNode.State;
import org.jenkinsci.plugins.coordinator.utils.TraversalHandler;
import org.jenkinsci.plugins.coordinator.utils.TreeNodeUtils;

import javax.servlet.ServletException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;


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
								= new ConcurrentHashMap<String, AbstractBuild<?, ?>>();
	
	protected ConcurrentHashMap<String, AbstractBuild<?, ?>> getActiveBuildMap() {
		return activeBuildMap;
	}

	// since there is no ConcurrentHashSet...
	private ConcurrentHashMap<String, Boolean> executedNodeIdMap = new ConcurrentHashMap<String, Boolean>();
	private CoordinatorBuild coordinatorBuild;
	
	private BuildListener listener;
	
	// avoid a sudden peak thread creation in memory
	private ExecutorService executorPool;
	
	// for updating the buildNumber in CoordinatorParameterValue to display in history page
	// so as request parameter need to be updated
	private Map<String, TreeNode> parameterMap;
	
	// only HashMap is okay
	private Set<String> failedParentNodeSet;
	
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
	
	private boolean isDeactive(TreeNode node){
		State state = node.getState();
		return state.disabled || !(state.checked || state.undetermined);
	}
	
	private boolean isOkayToKickoff(TreeNode node) {
		TreeNode origin = node;
		boolean result = true;
		// recursively goes up the parent see if any nodeId in failedParentNodeSet
		// if not 21_L will kickoff anyway
//		Root_S_breaking
//		|-- 1_S_breaking
//		|	|-- 11_L
//		|	|__ 12_L_Failure
//		|__ 2_S_breaking
//			|-- 21_L
//			|__ 22_L
		while(null != (node=node.getParent()) ){
			if( failedParentNodeSet.contains(node.getId()) ){
				result = false;
				break;
			}
		}
		if(!result && !origin.isLeaf()){
			this.parentChildrenMap.remove(origin.getId());
		}
		return result;
	}
	/**
	 * set up failedParentNodeSet, parameterMap and parentChildrenMap
	 */
	private void prepareExecutionPlan() {
		TreeNode buildRootNode = this.coordinatorBuild.getOriginalExecutionPlan();
		TreeNode requestRootNode = null;
		ParametersAction parametersAction = this.coordinatorBuild.getAction(ParametersAction.class);
		
		CoordinatorParameterValue parameter = (CoordinatorParameterValue) parametersAction.getParameter(CoordinatorParameterValue.PARAM_KEY);
		if (parameter != null) {
			// Use the configured execution plan above unless there's a requested change for this specific build via the "executionPlan" parameter.
			requestRootNode = parameter.getValue();
			TreeNodeUtils.mergeState4Execution(buildRootNode, requestRootNode);
		} else {
			requestRootNode = buildRootNode.clone(true);
		}
		
		// parameterMap for display build number in history page
		parameterMap = new HashMap<String, TreeNode>();
		for(TreeNode node: TreeNodeUtils.getFlatNodes(requestRootNode, false)){
			parameterMap.put(node.getId(), node);
		}
		
		TreeNodeUtils.preOrderTraversal(buildRootNode, new TraversalHandler(){
			@Override
			public boolean doTraversal(TreeNode node) {
				if(isDeactive(node)){
					return false;
				}
				if(!node.isLeaf()){
					prepareParentChildrenMap(node, node.shouldChildrenParallelRun());
				}
				return true;
			}
		});
		
		failedParentNodeSet = new HashSet<String>();
	}

	@SuppressFBWarnings(value="RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
	/*package*/ void kickOffBuild(TreeNode node){
		if(executorPool.isShutdown()
				|| isDeactive(node)
				|| !isOkayToKickoff(node)){
			// patch-up for serial build node.getChildren().get(0) is a not checked/determined node
			postBuild(node);
			return;
		}
		if(node.isLeaf()){
			if( executedNodeIdMap.containsKey(node.getId()) ){
				// fix #36, non-blocking postBuild double triggering jobs that are already executed
				return;
			}
			executedNodeIdMap.put(node.getId(), Boolean.TRUE);
			Authentication auth = Jenkins.getAuthentication();

			executorPool.submit(new Execution(node, auth), node);
		} else if(node.shouldChildrenParallelRun()){
			for(TreeNode child: node.getChildren()){
				kickOffBuild(child);
			}
		} else {
			kickOffBuild(node.getChildren().get(0));
		}
	}

	ArrayList<Action> prepareJobActions(
			final Job<?, ?> atomicProject) {
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

	/**
	 * This may kickoff another build
	 * @param node
	 */
	protected void postBuild(TreeNode node) {
		TreeNode parent = node.getParent();
		if(parent == null){
			// means node == root => true
			// do nothing to wait for the polling in #perform() to end
			return;
		}
		Map<String, TreeNode> childMap = this.parentChildrenMap.get(parent.getId());
		if( null == childMap ){
			// possible some child under the same breaking parent already failed
			return;
		}
		childMap.remove(node.getId());
		
		if(parent.shouldChildrenSerialRun()){
			// since it's LinkedHashMap in the creation, the insertion order still resists
			Iterator<TreeNode> iter = childMap.values().iterator();
			if(iter.hasNext()){
				kickOffBuild(iter.next());	
			}
		}
		if(childMap.isEmpty()) {
			// removing this parent node for the loop in execute() to end 
			this.parentChildrenMap.remove(parent.getId());
			postBuild(parent);
		}
		
	}

	private static Item getProject(TreeNode node) {
		Jenkins instance = Jenkins.getInstance();
		if(instance == null) {
			throw new IllegalStateException("Jenkins is not ready.");
		}
		return instance.getItemByFullName(node.getText());
	}

	private void doPostBuildLog(final TreeNode node, Result result) {
		String jobName = node.getText();
		synchronized(listener){
			try {
				StringBuilder sb = new StringBuilder(100);
				sb.append("/").append(getProject(node).getUrl());
				listener.getLogger().print("Atomic Job: ");
				listener.hyperlink(sb.toString(), jobName);
				sb.append(node.getBuildNumber()).append("/console");
				listener.getLogger().print("  ");
				listener.hyperlink(sb.toString(), "#" + node.getBuildNumber());
				listener.getLogger().format(" Completed, Result: %s%n", result);
			} catch (IOException e) {
				// only change to log the message without hyper link
				listener.getLogger().format(
						"Item(%1$s) #%2$-6d Completed, Result: %3$s%n", jobName,
						node.getBuildNumber(), result);
			}
		}
	}

	private Item prepareProxiedProject(final TreeNode node) {
		try {
			Job atomicProject = (Job<?, ?>) getProject(node);
			if (atomicProject == null) {
				formattedLog("Atomic Job: %s not found\n", node.getText());
				onAtomicJobFailure(node);
				return null;
			}
			if (!atomicProject.isBuildable()) {
				formattedLog("Atomic Job: %s is either disabled or new job's configuration not saved[refer to hudson.model.Job#isHoldOffBuildUntilSave]\n",
						node.getText());
				onAtomicJobFailure(node);
				return null;
			}
			Enhancer en = new Enhancer();
			en.setClassLoader(this.getClass().getClassLoader());
			en.setSuperclass(atomicProject.getClass());
			en.setCallback(new InjectedProjectProxy(atomicProject, node));
			atomicProject = (AbstractProject<?, ?>) en.create(new Class<?>[]{
							ItemGroup.class, String.class},
					new Object[]{atomicProject.getParent(), atomicProject.getName()});
			return atomicProject;
		} catch (RuntimeException e) {
			listener.getLogger().print(e.getMessage());
		}
		return null;
	}


	private ParametersAction prepareParametersAction(
			Job<?, ?> atomicProject) {
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
	
	private void softShutdown(){
		if(!executorPool.isShutdown()){
			executorPool.shutdown();
		}
	}
	
	private void stopSharedAncestorRunningNodes(List<String> sharedBreakingAncestorNodeIds) {
		// just a relevant rare case, I just need the parent reference on each node...
		List<TreeNode> nodes = TreeNodeUtils.getFlatNodes(this.coordinatorBuild.getOriginalExecutionPlan(), false);
		HashMap<String, TreeNode> idNodeMap = new HashMap<String, TreeNode>( nodes.size()*3 );
		for(TreeNode node: nodes){
			idNodeMap.put(node.getId(), node);
		}
		for(Map.Entry<String, AbstractBuild<?, ?>> entry: this.activeBuildMap.entrySet()){
			String nodeId = entry.getKey();
			TreeNode node = idNodeMap.get(nodeId);
			List<String> activeBreakingAncestorNodeIds = prepareBreakingAncestorIds(node, false);
			activeBreakingAncestorNodeIds.retainAll(sharedBreakingAncestorNodeIds);
			if(!activeBreakingAncestorNodeIds.isEmpty()){
				AbstractBuild<?, ?> build = entry.getValue();
				try{
					build.doStop();
				} catch(Exception e){
					formattedLog("softShutdown on %s failed with %s", build.getFullDisplayName(), e);
				}
			}
		}
	}
	
	private List<String> prepareBreakingAncestorIds(TreeNode node, boolean shouldStopOnNonBreakingParent) {
		ArrayList<String> result = new ArrayList<String>();
		while(null != (node=node.getParent()) ){
			if(node.getState().breaking){
				result.add(node.getId());
			} else {
				// if it becomes non breaking one then stop right here no more climbing up
				if(shouldStopOnNonBreakingParent){
					break;
				}
			}
		}
		return result;
	}


	/**
	 * Node should not be parent node
	 * @param node
	 */
	protected void onAtomicJobFailure(TreeNode node){
		TreeNode origin = node;
		List<String> sharedBreakingAncestorNodeIds = this.prepareBreakingAncestorIds(node, true);
		this.failedParentNodeSet.addAll(sharedBreakingAncestorNodeIds);
		
		TreeNode rootNode = coordinatorBuild.getOriginalExecutionPlan();
		TreeNode parent = origin.getParent();
		
		Result result = Result.UNSTABLE;
		if(parent.getState().breaking){
			stopSharedAncestorRunningNodes(sharedBreakingAncestorNodeIds);
			if(sharedBreakingAncestorNodeIds.contains(rootNode.getId()) && rootNode.getState().breaking){
				// sharedBreakingParentNodeIds.contains means already traversed up to the root node
				// rootNodeBreaking means the whole executorPool should shutdown();
				softShutdown();
				result = Result.FAILURE;
			}
		} 
		this.coordinatorBuild.setResult(result);
	}
	
	/**
	 * just for displaying the build # in the CoordinatorParameterValue
	 * @author Ace Han
	 *
	 */
	/*package*/ class InjectedProjectProxy implements MethodInterceptor {

		private Job<?, ?> originalAtomicProject;
		private TreeNode node;
		
		/*package*/ InjectedProjectProxy(Job<?, ?> project, TreeNode node) {
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
					subTasks.add(index, (BuildableItem)obj);
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
			// ref #29, atomic jobs not found when security is enabled 
			// make coordinator job be able to kick off those atomic jobs without READ permission as designed 
			SecurityContextHolder.getContext().setAuthentication(auth);
			Item atomicProject = prepareProxiedProject(node);
			if(atomicProject == null) return;
			List<Action> actions = prepareJobActions((Job<?,?>) atomicProject);
			formattedLog("Atomic Job ( %s ) Triggered\n", jobName);
			QueueTaskFuture<Executable> future = getRunningFuture(node, (BuildableItem) atomicProject, actions);
			Run<?, ?> targetBuild;
			if(future == null) return;
			// TODO should make it configurable
			int time = 8;
			try {
				future.get(time, TimeUnit.HOURS);
			} catch (TimeoutException e) {
				formattedLog("Atomic Job(%1$s-%2$s) Time out, waited for %3$d %s, Exception:\n%4$s\n", 
						node.getId(), jobName, time, TimeUnit.HOURS, e);
				// PerformExecutor.this.onAtomicJobFailure(node);
			} catch (Exception e) {
				formattedLog("Atomic Job(%1$s-%2$s#%3$s) failed, Exception:\n%4$s\n",
						node.getId(), jobName, 
						node.getBuildNumber(),	// since this build# got filled in proxiedProject#newBuild
						e);
				// PerformExecutor.this.onAtomicJobFailure(node);
			} finally{
				targetBuild = activeBuildMap.remove(node.getId());
			}
			if(targetBuild == null){
				return;
			}
			
			Result result = targetBuild.getResult();
			doPostBuildLog(node, result);
			if(!(result == Result.SUCCESS || result == Result.UNSTABLE)) {
				PerformExecutor.this.onAtomicJobFailure(node);
			}
			postBuild(node);
		}

		@SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
		private QueueTaskFuture<Executable> getRunningFuture(TreeNode node,	
				BuildableItem atomicProject, List<Action> actions) {
			Jenkins instance = Jenkins.getInstance();
			if(instance == null) {
				throw new IllegalStateException("Jenkins is not ready.");
			}

			int rescheduleCount = 0;
			// TODO should be tested
			while(rescheduleCount ++ < 3){
				ScheduleResult result = instance.getQueue().schedule2(atomicProject, 0, actions);
				if(result.isRefused()){
					formattedLog("Jenkins refused to add Atomic Job ( %s ), considered as a failure, aborting entire coordinator job\n", 
								node.getText());
					// In this case, I take it as sth. wrong in Jenkins, a force shutdown to check it out
					PerformExecutor.this.onAtomicJobFailure(node);
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
