package org.jenkinsci.plugins.coordinator.model;

import hudson.Functions;
import hudson.Util;
import hudson.model.Action;
import hudson.model.Build;
import hudson.model.ParameterValue;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.CauseAction;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import jenkins.model.Jenkins;
import net.sf.json.JSON;
import net.sf.json.JSONNull;
import net.sf.json.JSONObject;

import org.apache.commons.jelly.JellyContext;
import org.apache.commons.jelly.JellyException;
import org.apache.commons.jelly.Script;
import org.apache.commons.jelly.XMLOutput;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.MetaClass;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.WebApp;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.jelly.JellyClassTearOff;


public class CoordinatorBuild extends Build<CoordinatorProject, CoordinatorBuild> {
	
	// fixes #4, Histories will get flushed and keep the last triggered build info after jenkins instance restart
	// if we initialize it in the constructor, 
	// it will flush what was already got unmarshaled from disk build.xml
	// hierarchy CoordinatorBuild -> Build -> AbstractBuild -> Run#reload()#getDataFile().unmarshal(this)
	private List<List<? extends Action>> oldActions;

	private transient PerformExecutor performExecutor;
	
	private TreeNode originalExecutionPlan;
	
	private TreeNode defaultExecutionPlan;
	
	private transient Map<String, Integer> tableRowIndexMap;
	
	public CoordinatorBuild(CoordinatorProject project) throws IOException {
		super(project);
	}

	public CoordinatorBuild(CoordinatorProject project, File buildDir) throws IOException {
        super(project, buildDir);
    }
	
	/*package*/ TreeNode getOriginalExecutionPlan() {
		return originalExecutionPlan;
	}

	public void setOriginalExecutionPlan(TreeNode originalExecutionPlan) {
		this.originalExecutionPlan = originalExecutionPlan;
	}
	
	public TreeNode getDefaultExecutionPlan() {
		return defaultExecutionPlan;
	}

	public void setDefaultExecutionPlan(TreeNode defaultExecutionPlan) {
		this.defaultExecutionPlan = defaultExecutionPlan;
	}

	
	public void addOldActions(List<? extends Action> oldActions) {
		this.getOldActions().add(oldActions);
	}
	
	public List<List<? extends Action>> getOldActions(){
		if(this.oldActions == null){
			this.oldActions = new ArrayList<List<? extends Action>>();
		}
		return this.oldActions;
	}
	
	public List<List<? extends Action>> getReversedHistoricalActions(){
		ArrayList<List<? extends Action>> withCurrentActions = new ArrayList<List<? extends Action>>(this.getOldActions());
		withCurrentActions.add(super.getAllActions());
		ArrayList<List<? extends Action>> result = new ArrayList<List<? extends Action>>(withCurrentActions.size());
		for(List<? extends Action> actions: withCurrentActions){
			// since foldCauseIntoOne make causeAction in the end of the list
			// for ui displaying purpose, might as well doing this to ensure causeAction ahead of other actions
			List<Action> tmp = new ArrayList<Action>(actions);
			Action pa = null;
	        for (Action a : tmp) {
	            if (a instanceof ParametersAction) {
	                tmp.remove(a);
	                pa = a;
	            }
	        }
	        tmp.add(pa);
	        result.add(tmp);
		}
		Collections.reverse(result);
		return result;
	}

	@Override
	@RequirePOST
	public synchronized HttpResponse doStop() throws IOException,
			ServletException {
		if(performExecutor != null){
			performExecutor.shutdown();
		}
		return super.doStop();
	}

	public void setPerformExecutor(PerformExecutor performExecutor) {
		// just for a clean stop
		this.performExecutor = performExecutor;
	}
	
	/**
	 *  currently being invoked in *.jelly file 
	 * @return
	 */
	public AtomicBuildInfo getExecutionPlanInfo(){
		return prepareAtomicBuildInfo(this.originalExecutionPlan);
	}

	private void prepareTableRowIndexMap() {
		if(this.tableRowIndexMap == null){
			this.tableRowIndexMap = new HashMap<String, Integer>();
			List<TreeNode> list = this.originalExecutionPlan.getFlatNodes(true);
			for(int i=0; i<list.size(); i++){
				TreeNode node = list.get(i);
				this.tableRowIndexMap.put(node.getId(), i);
			}
		}
	}

	public JSON doPollActiveAtomicBuildStatus(StaplerRequest req) {
		if(this.performExecutor == null){
			// no build or refresh or reload from disk
			return JSONNull.getInstance();
		}
		Set<Entry<String, AbstractBuild<?, ?>>> entrySet = this.performExecutor.getActiveBuildMap().entrySet();
		// since every http request is state-less, and doXXX is catering that tableRowIndexMap may not get initialized
		// we need to ensure that we are not geting an empty tableRowIndexMap
		this.prepareTableRowIndexMap();
		
		Map<String, String> result = new HashMap<String, String>(entrySet.size()*2 + 3);
		TreeNode dummyNode = prepareDummyTreeNode();
		JellyContext context = prepareJellyContextVariables(req);
		for(Map.Entry<String, AbstractBuild<?, ?>> entry: entrySet){
			AbstractBuild<?, ?> build = entry.getValue();
			AtomicBuildInfo abi = new AtomicBuildInfo();
			abi.build = build;
			abi.treeNode = dummyNode;	// just taking advantage of tableRow.jelly
			
			// already got initialized by prepareTableRowIndexMap()
			abi.tableRowIndex = this.tableRowIndexMap.get(entry.getKey()); 
			result.put(entry.getKey(), getBuildInfoScriptAsString(context, abi));
		}
		return JSONObject.fromObject(result);
	}
	
	public String doAtomicBuildResultTableRowHtml(StaplerRequest req, @QueryParameter String nodeId, 
			 @QueryParameter String jobName, @QueryParameter int buildNumber){
		AtomicBuildInfo abi = new AtomicBuildInfo();
		abi.build = retrieveTargetBuild(jobName, buildNumber);
		if(abi.build == null){
			// Here should be those jobs that are executing not much long, say <5 seconds.
			// They may get no chance to update its tableRow.jelly during window.setTimeout interval by some scenario
			// I might as well give it a full scan for a try to retrieve the correct buildNumber
			if(StringUtils.isNotEmpty(nodeId)){
				buildNumber = tryRetrievingBuildNumber(nodeId);
				abi.build = retrieveTargetBuild(jobName, buildNumber);
			}
			if(abi.build == null){
				String errorMsg = "Insufficient parameters to retrieve specific build, jobName: " 
						+ jobName + " build #: "+ buildNumber;
				LOGGER.warning(errorMsg);
				return prepareBuildStatusErrorMessage(new IllegalArgumentException(errorMsg));
			}
		}
		abi.treeNode = prepareDummyTreeNode();
		prepareTableRowIndexMap();
		abi.tableRowIndex = this.tableRowIndexMap.get(nodeId);
		JellyContext context = prepareJellyContextVariables(req);
		return getBuildInfoScriptAsString(context, abi);
	}

	private int tryRetrievingBuildNumber(String nodeId) {
		List<TreeNode> breadthList = this.getOriginalExecutionPlan().getFlatNodes(false);
		// since all leaf nodes are in the end of the list
		Collections.reverse(breadthList);
		
		for(TreeNode node: breadthList){
			if(nodeId.equals(node.getId())){
				return node.getBuildNumber();
			}
		}
		return 0;
	}

	private TreeNode prepareDummyTreeNode() {
		TreeNode dummyNode = new TreeNode();
		dummyNode.setText("Dummy for Polling Active Atomic Build Info");
		return dummyNode;
	}
	
	protected String getBuildInfoScriptAsString(JellyContext context, AtomicBuildInfo abi) {
		context.setVariable("it", abi);
        MetaClass mc = WebApp.getCurrent().getMetaClass(this.getClass());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(mc.classLoader.loader);
        try {
        	XMLOutput output = XMLOutput.createXMLOutput(baos);
			Script script = mc.loadTearOff(JellyClassTearOff.class).findScript("tableRow.jelly");
			script.run(context, output);
			return baos.toString("UTF-8");
		} catch (JellyException e) {
			LOGGER.warning("Exception in tableRow.jelly:\n"+e);
			return prepareBuildStatusErrorMessage(e);
		} catch (UnsupportedEncodingException e) {
			LOGGER.warning("Could not resolve tableRow.jelly in the specific charset:\n" + e);
			return prepareBuildStatusErrorMessage(e);
		} finally {
			Thread.currentThread().setContextClassLoader(old);
		}

	}

	protected JellyContext prepareJellyContextVariables(StaplerRequest req) {
		JellyContext context = new JellyContext();
        // let Jelly see the whole classes
		context.setClassLoader( WebApp.getCurrent().getClassLoader());
        // fix rootURL, resURL, imagesURL is missing
        Functions.initPageVariables(context);
        // this variable is needed to make "jelly:fmt" taglib work correctly
        context.setVariable("org.apache.commons.jelly.tags.fmt.locale",req.getLocale());
		return context;
	}

	private String prepareBuildStatusErrorMessage(Exception e) {
		return "<div class='jstree-wholerow jstree-table-row' style='background-color:#ffebeb;'>"
				+"<div class='jstree-table-col jobStatus'>&nbsp;</div>"
				+ "<div class='jstree-table-col lastDuration'>Server side error. Please checkout the server log</div></div>";
	}

	public AtomicBuildInfo prepareAtomicBuildInfo(TreeNode node){
		prepareTableRowIndexMap();
		AtomicBuildInfo abi = new AtomicBuildInfo();
		abi.treeNode = node;
		abi.tableRowIndex = tableRowIndexMap.get(node.getId());
		List<TreeNode> children = node.getChildren();
		if(children.isEmpty()){
			abi.build = retrieveTargetBuild(node.getText(), node.getBuildNumber());
		} 
		abi.children = new ArrayList<AtomicBuildInfo>(children.size());
		for(TreeNode child: children){
			AtomicBuildInfo abiChild = prepareAtomicBuildInfo(child);
			abi.children.add(abiChild);
		}
		return abi;
	}

	private AbstractBuild<?, ?> retrieveTargetBuild(String projectName,
			int buildNumber){
		AbstractProject<?, ?> project = (AbstractProject<?, ?>)Jenkins.getInstance().getItem(projectName);
		if(project == null){
			return null;
		}
		return (AbstractBuild<?, ?>)project.getBuildByNumber(buildNumber);
	}
	
	public List<ParameterDefinition> getParameterDefinitionsWithValues(){
		ArrayList<ParameterDefinition> result = new ArrayList<ParameterDefinition>();
		List<ParameterValue> pvs = this.getAction(ParametersAction.class).getParameters();
		List<ParameterDefinition> pds = super.getParent().getProperty(ParametersDefinitionProperty.class).getParameterDefinitions();
		for(ParameterDefinition pd: pds){
			for(ParameterValue pv: pvs){
				ParameterDefinition toBeTested = pd.copyWithDefaultValue(pv);
				// since it's a common pattern that copyWithDefaultValue will generate a new one
				// I'm now taking this trick
				if(pd != toBeTested){
					pd = toBeTested;
					break;
				}
			}
			result.add(pd);
		}
		
		return result;
	}
	
	/**
	 * For display purpose
	 * @author Ace Han
	 *
	 */
	public static class AtomicBuildInfo {
		public TreeNode treeNode;
		public int tableRowIndex;
		public AbstractBuild<?, ?> build;
		
		public List<AtomicBuildInfo> children;
	}
	
	private static final Logger LOGGER = Logger.getLogger(CoordinatorBuild.class.getName());
}
