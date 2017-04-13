package org.jenkinsci.plugins.coordinator.model;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Functions;
import hudson.model.*;
import jenkins.model.Jenkins;
import net.sf.json.JSON;
import net.sf.json.JSONNull;
import net.sf.json.JSONObject;
import org.apache.commons.jelly.JellyContext;
import org.apache.commons.jelly.JellyException;
import org.apache.commons.jelly.Script;
import org.apache.commons.jelly.XMLOutput;
import org.jenkinsci.plugins.coordinator.utils.TreeNodeUtils;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.MetaClass;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.WebApp;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.kohsuke.stapler.jelly.JellyClassTearOff;

import javax.servlet.ServletException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.logging.Logger;


public class CoordinatorBuild extends Build<CoordinatorProject, CoordinatorBuild> {

    // fixes #4, Histories will get flushed and keep the last triggered build info after jenkins instance restart
    // if we initialize it in the constructor,
    // it will flush what was already got unmarshaled from disk build.xml
    // hierarchy CoordinatorBuild -> Build -> AbstractBuild -> Run#reload()#getDataFile().unmarshal(this)
    private List<List<? extends Action>> oldActions;

    private transient PerformExecutor performExecutor;

    private TreeNode originalExecutionPlan;

    private transient Map<String, Integer> tableRowIndexMap;

    /*package*/ TreeNode getOriginalExecutionPlan() {
        return originalExecutionPlan;
    }

    public void setOriginalExecutionPlan(TreeNode originalExecutionPlan) {
        this.originalExecutionPlan = originalExecutionPlan;
    }

    public CoordinatorBuild(CoordinatorProject project) throws IOException {
        super(project);
    }

    public CoordinatorBuild(CoordinatorProject project, File buildDir) throws IOException {
        super(project, buildDir);
    }

    public void addOldActions(List<? extends Action> oldActions) {
        this.getOldActions().add(oldActions);
    }

    public List<List<? extends Action>> getOldActions(){
        if(this.oldActions == null){
            this.oldActions = new ArrayList<>();
        }
        return this.oldActions;
    }

    public List<List<? extends Action>> getReversedHistoricalActions(){
        ArrayList<List<? extends Action>> withCurrentActions = new ArrayList<>(this.getOldActions());
        withCurrentActions.add(super.getAllActions());
        ArrayList<List<? extends Action>> result = new ArrayList<>(withCurrentActions.size());
        for(List<? extends Action> actions: withCurrentActions){
            // since foldCauseIntoOne make causeAction in the end of the list
            // for ui displaying purpose, might as well doing this to ensure causeAction ahead of other actions
            List<Action> tmp = new ArrayList<>(actions);
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
    public HttpResponse doStop() throws IOException,
            ServletException {
        synchronized (this) {
            if(performExecutor != null){
                performExecutor.shutdown();
            }
        }

        return super.doStop();
    }

    public void setPerformExecutor(PerformExecutor performExecutor) {
        // just for a clean stop
        synchronized (this) {
            this.performExecutor = performExecutor;
        }
    }

    /**
     *  currently being invoked in *.jelly file
     * @return
     */
    public AtomicBuildInfo getExecutionPlanInfo(){
        return prepareAtomicBuildInfo(this.originalExecutionPlan, false);
    }

    private void prepareTableRowIndexMap() {
        if(this.tableRowIndexMap == null){
            this.tableRowIndexMap = new HashMap<>();
            List<TreeNode> list = TreeNodeUtils.getFlatNodes(this.originalExecutionPlan, true);
            for(int i=0; i<list.size(); i++){
                TreeNode node = list.get(i);
                this.tableRowIndexMap.put(node.getId(), i);
            }
        }
    }


    /**
     * ref #14, Status of coordinator job should be synced with the job status
     * @param req
     * @return
     */
    public String doBuildCaptionHtml(StaplerRequest req){
        JellyContext context = prepareJellyContextVariables(req);
        context.setVariable("it", this);
        return getCoordinatorBuildJellyScriptAsString(context, "buildCaption.jelly");
    }

    /**
     * Return current build's status
     * @param req
     * @return a map of <nodeId, BuildTableRowHtml(tableRow.jelly)>
     */
    public JSON doPollActiveAtomicBuildsTableRowHtml(StaplerRequest req){

        synchronized (this) {
            if(this.performExecutor == null){
                // no build or refresh or reload from disk
                return JSONNull.getInstance();
            }
        }

        // children under this rootNode will get its corresponding build number
        // if it has already been built
        TreeNode rootNode = originalExecutionPlan;
        ParametersAction parametersAction = getAction(ParametersAction.class);
        CoordinatorParameterValue parameter = (CoordinatorParameterValue) parametersAction.getParameter(CoordinatorParameterValue.PARAM_KEY);
        if (parameter != null) {
            // Use the configured execution plan above unless there's a requested change for this specific build via the "executionPlan" parameter.
            rootNode = parameter.getValue();
        }

        // doesnt matter if byDepth or not for the case
        // rootNode included
        List<TreeNode> nodes = TreeNodeUtils.getFlatNodes(rootNode, true);
        JellyContext context = prepareJellyContextVariables(req);
        Map<String, String> result = new HashMap<String, String>();
        long now = Functions.getCurrentTime().getTime();
        long timeDelta = 10 * 1000; // 10 seconds
        for(TreeNode node: nodes){
            if(!node.isLeaf()){
                continue;
            }
            AtomicBuildInfo abi = prepareAtomicBuildInfo(node, true);
            if(null == abi.build){
                continue;
            } else if(abi.build.isBuilding()){
                result.put(node.getId(), getBuildInfoScriptAsString(context, abi));
            } else if(abi.build.getStartTimeInMillis() + abi.build.getDuration() + timeDelta > now){
                // for those just finished and not getting a chance to be updated in the jelly page
                result.put(node.getId(), getBuildInfoScriptAsString(context, abi));
            }
        }
        return JSONObject.fromObject(result);
    }

    protected String getBuildInfoScriptAsString(JellyContext context, AtomicBuildInfo abi) {
        context.setVariable("it", abi);
        return getCoordinatorBuildJellyScriptAsString(context, "tableRow.jelly");
    }

    private String getCoordinatorBuildJellyScriptAsString(JellyContext context, String scriptPath) {
        MetaClass mc = WebApp.getCurrent().getMetaClass(this.getClass());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(mc.classLoader.loader);
        try {
            XMLOutput output = XMLOutput.createXMLOutput(baos);
            Script script = mc.loadTearOff(JellyClassTearOff.class).findScript(scriptPath);
            script.run(context, output);
            return baos.toString("UTF-8");
        } catch (JellyException e) {
            LOGGER.warning("Exception in "+scriptPath+":\n"+e);
            return prepareBuildStatusErrorMessage(e);
        } catch (UnsupportedEncodingException e) {
            LOGGER.warning("Could not resolve "+scriptPath+" in the specific charset:\n" + e);
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
        // context.setVariable("timezone", jsDetectedTimezone);
        // http://stackoverflow.com/questions/3001260/how-to-detect-client-timezone
        // unless there is a global setting explicitly set
        return context;
    }

    private String prepareBuildStatusErrorMessage(Exception e) {
        return "<div class='jstree-wholerow jstree-table-row' style='background-color:#ffebeb;'>"
                +"<div class='jstree-table-col jobStatus'>&nbsp;</div>"
                + "<div class='jstree-table-col lastDuration'>Server side error. Please checkout the server log</div></div>";
    }

    @SuppressFBWarnings(value = "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", justification = "abi.treeNode and abi.tableRowIndex will be used in page.")
    public AtomicBuildInfo prepareAtomicBuildInfo(TreeNode node, boolean skipChildren){
        // this method will be called in page histories.jelly, so take care
        prepareTableRowIndexMap();
        AtomicBuildInfo abi = new AtomicBuildInfo();
        abi.treeNode = node;
        abi.tableRowIndex = tableRowIndexMap.get(node.getId());
        List<TreeNode> children = node.getChildren();
        if(children.isEmpty()){
            abi.build = retrieveTargetBuild(node.getText(), node.getBuildNumber());
        }
        if(!skipChildren){
            abi.children = new ArrayList<AtomicBuildInfo>(children.size());
            for(TreeNode child: children){
                AtomicBuildInfo abiChild = prepareAtomicBuildInfo(child, skipChildren);
                abi.children.add(abiChild);
            }
        }
        return abi;
    }

    private Run<?, ?> retrieveTargetBuild(String projectName,
                                                    int buildNumber){
        Jenkins jenkins = Jenkins.getInstance();
        if(jenkins == null) {
            throw new IllegalStateException("Jenkins is not started yet...");
        }

        Job<?, ?> project = (Job<?, ?>)jenkins.getItemByFullName(projectName);
        if(project == null){
            return null;
        }
        return project.getBuildByNumber(buildNumber);
    }

    public List<ParameterDefinition> getParameterDefinitionsWithValues(){
        ArrayList<ParameterDefinition> result = new ArrayList<ParameterDefinition>();
        List<ParameterValue> pvs = this.getAction(ParametersAction.class).getParameters();
        List<ParameterDefinition> pds = super.getParent().getProperty(ParametersDefinitionProperty.class).getParameterDefinitions();

        // fix #12, Execution parameter values display disorder
        for(int i=0; i<pds.size(); i++){
            // based on index is okay for simplicity sake
            ParameterDefinition pd = pds.get(i);
            ParameterValue pv = pvs.get(i);
            ParameterDefinition pdWithValue = pd.copyWithDefaultValue(pv);
            result.add(pdWithValue);
        }

        return result;
    }

    public <T extends Action> T getAction(Class<T> type) {
        T action = super.getAction(type);
        if(type != ParametersAction.class){
            return action;
        }
        // fix #31, NullPointerException while coordinator job triggered by
        // an upstream job with a plugin like parameterized-trigger-plugin
        ParametersAction pa = (ParametersAction) action;
        if (pa == null) {
            pa = new ParametersAction();
        }

        CoordinatorParameterValue parameter = (CoordinatorParameterValue)pa.getParameter(
                CoordinatorParameterValue.PARAM_KEY);
        if(parameter != null){
            return action;
        }
        CoordinatorParameterValue pv = new CoordinatorParameterValue(CoordinatorParameterValue.PARAM_KEY,
                "", getOriginalExecutionPlan());
        T cast = type.cast(pa.createUpdated(Arrays.asList(pv)));
        this.replaceAction(cast);
        return cast;
    }

    /**
     * For display purpose
     * @author Ace Han
     *
     */
    public static class AtomicBuildInfo {
        public TreeNode treeNode;
        public int tableRowIndex; // for odd or even in page rendering
        public Run<?, ?> build;

        public List<AtomicBuildInfo> children;
    }

    private static final Logger LOGGER = Logger.getLogger(CoordinatorBuild.class.getName());
}
