package org.jenkinsci.plugins.coordinator.model;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;

import java.io.IOException;

import net.sf.json.JSONException;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * 
 * For project's builder part configuration
 * @author Ace Han
 *
 */
public class CoordinatorBuilder extends Builder {
	
	private TreeNode executionPlan;
	
	public CoordinatorBuilder(){
		setExecutionPlan(TreeNode.EMPTY_ROOT);
	}
	
	@DataBoundConstructor
	public CoordinatorBuilder(TreeNode executionPlan){
		setExecutionPlan(executionPlan);
	}
	
	public TreeNode getExecutionPlan() {
		return executionPlan;
	}

	public void setExecutionPlan(TreeNode executionPlan) {
		this.executionPlan = executionPlan;
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws InterruptedException, IOException {
		assert build instanceof CoordinatorBuild;
		
		CoordinatorBuild cb = (CoordinatorBuild) build;
		
		// TODO make this 30 configurable
		PerformExecutor performExecutor = new PerformExecutor(cb, listener, 30);
		return performExecutor.execute();
	}
	
	/**
	 * It's really weird that jenkins doesn't load this plugin's descriptor in test
	 * 
	 * It will break the test in some way, 
	 * for example, CoordinatorBuild result will turn Result.UNSTABLE to Result.FAILURE
	 * refer to {@code hudson.model.AbstractBuild#getBuildStepName}, 
	 * {@code hudson.tasks.Builder#getDescriptor}, 
	 * {@code jenkins.model.Jenkins#getDescriptorOrDie}
	 * @return
	 */
	public Descriptor<Builder> getDescriptor(){
		try{
			return super.getDescriptor();
		} catch(AssertionError e){
			// this scenario should be only to unit test 
			return new DescriptorImpl();
		}
	}
	
	@Extension
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
		
		@Override
		public boolean isApplicable(@SuppressWarnings("rawtypes") 
									Class<? extends AbstractProject> jobType) {
			// Only available to CooradinatorProject
			return CoordinatorProject.class.isAssignableFrom(jobType);
		}
		
		@Override
		public String getDisplayName() {
			return "Coordinator";
		}

		@Override
		public Builder newInstance(StaplerRequest req, JSONObject formData)
				throws FormException {
			JSONObject jsonObject = JSONObject.fromObject(
											formData.getString("executionPlan"), 
											TreeNode.JSON_CONFIG);
			TreeNode executionPlan = (TreeNode)JSONObject.toBean(jsonObject, TreeNode.JSON_CONFIG);
			return new CoordinatorBuilder(executionPlan);
		}
		
		public FormValidation doCheckExecutionPlan(@QueryParameter String value) {
			// this check maybe not that much meaningful
			try{
				JSONObject.fromObject(value, TreeNode.JSON_CONFIG);
				return FormValidation.ok();
			} catch(JSONException e){
				return FormValidation.error(e.getMessage());
			}
		}
		
	}
}
