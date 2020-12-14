package com.oracle.cloud.baremetal.jenkins;

import hudson.Extension;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.slaves.Cloud;
import hudson.slaves.CloudProvisioningListener;
import hudson.slaves.NodeProvisioner;
import jenkins.model.Jenkins;
import com.oracle.cloud.baremetal.jenkins.BaremetalCloud;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * based on
 * https://github.com/jenkinsci/kubernetes-plugin/blob/master/src/main/java/io/jenkins/plugins/kubernetes/NoDelayProvisionerStrategy.java
 * under Apache 2.0 License
 * https://github.com/jenkinsci/kubernetes-plugin/blob/master/LICENSE
 * 
 * Implementation of {@link NodeProvisioner.Strategy} which will provision a new node immediately as
 * a task enter the queue.
 *
 * @author <a href="mailto:root@junwuhui.cn">runzexia</a>
 */
@Extension(ordinal = 100)
public class NoDelayProvisionerStrategy extends NodeProvisioner.Strategy {

    private static final Logger LOGGER = Logger.getLogger(NoDelayProvisionerStrategy.class.getName());
    private static final boolean DISABLE_NODELAY_PROVISING = Boolean.valueOf(
            System.getProperty("com.oracle.cloud.baremetal.jenkins.disableNoDelayProvisioning"));

    @Override
    public NodeProvisioner.StrategyDecision apply(NodeProvisioner.StrategyState strategyState) {
        if (DISABLE_NODELAY_PROVISING) {
            LOGGER.log(Level.FINE, "Provisioning not complete, NoDelayProvisionerStrategy is disabled");
            return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
        }

        final Label label = strategyState.getLabel();
         LOGGER.log(Level.INFO, "strategyState.getLabel()={0}", label);
        LoadStatistics.LoadStatisticsSnapshot snapshot = strategyState.getSnapshot();
        int availableCapacity =
                snapshot.getAvailableExecutors()   // live executors
                        + snapshot.getConnectingExecutors()  // executors present but not yet connected
                        + strategyState.getPlannedCapacitySnapshot()     // capacity added by previous strategies from previous rounds
                        + strategyState.getAdditionalPlannedCapacity();  // capacity added by previous strategies _this round_
        // change available capacity by the pool size
        //if (label.getDisplayName().indexOf("large") >= 0) {
          // anticipate less of the large shapes
          availableCapacity -= 2;
        //} else { 
          // more of other shapes... need to implement this as a property attached to templates.
          availableCapacity -= 3;
        //}
        int currentDemand = snapshot.getQueueLength();
        LOGGER.log(Level.INFO, "Available capacity={0}, currentDemand={1}",
                new Object[]{availableCapacity, currentDemand});
        if (availableCapacity < currentDemand) {
            List<Cloud> jenkinsClouds = new ArrayList<>(Jenkins.get().clouds);
            Collections.shuffle(jenkinsClouds);
            for (Cloud cloud : jenkinsClouds) {
                int workloadToProvision = currentDemand - availableCapacity;
                if (!(cloud instanceof BaremetalCloud)) continue;
                if (!cloud.canProvision(label)) {
                   LOGGER.log(Level.INFO, "cloud {0} can not provision label {1}", new Object[]{cloud.getDisplayName(), label});
                   continue;
                }
                for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
                    if (cl.canProvision(cloud, strategyState.getLabel(), workloadToProvision) != null) {
                        continue;
                    }
                }
                Collection<NodeProvisioner.PlannedNode> plannedNodes = cloud.provision(label, workloadToProvision);
                LOGGER.log(Level.INFO, "Planned {0} new nodes", plannedNodes.size());
                fireOnStarted(cloud, strategyState.getLabel(), plannedNodes);
                strategyState.recordPendingLaunches(plannedNodes);
                availableCapacity += plannedNodes.size();
                LOGGER.log(Level.INFO, "After provisioning, available capacity={0}, currentDemand={1}", new Object[]{availableCapacity, currentDemand});
                break;
            }
        }
        if (availableCapacity >= currentDemand) {
            LOGGER.log(Level.INFO, "Provisioning completed");
            return NodeProvisioner.StrategyDecision.PROVISIONING_COMPLETED;
        } else {
            LOGGER.log(Level.INFO, "Provisioning not complete, consulting remaining strategies");
            return NodeProvisioner.StrategyDecision.CONSULT_REMAINING_STRATEGIES;
        }
    }

    private static void fireOnStarted(final Cloud cloud, final Label label,
                                      final Collection<NodeProvisioner.PlannedNode> plannedNodes) {
        for (CloudProvisioningListener cl : CloudProvisioningListener.all()) {
            try {
                cl.onStarted(cloud, label, plannedNodes);
            } catch (Error e) {
                throw e;
            } catch (Throwable e) {
                LOGGER.log(Level.SEVERE, "Unexpected uncaught exception encountered while "
                        + "processing onStarted() listener call in " + cl + " for label "
                        + label.toString(), e);
            }
        }
    }
}
