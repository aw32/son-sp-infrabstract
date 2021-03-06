package sonata.kernel.placement.service;


import org.apache.bcel.generic.POP;
import org.apache.log4j.Logger;
import sonata.kernel.VimAdaptor.commons.DeployServiceData;
import sonata.kernel.placement.DatacenterManager;
import sonata.kernel.placement.config.PlacementConfigLoader;
import sonata.kernel.placement.config.PerformanceThreshold;
import sonata.kernel.placement.config.PlacementConfig;
import sonata.kernel.placement.config.PopResource;
import sonata.kernel.placement.config.SystemResource;

import java.util.ArrayList;
import java.util.List;


public class PlacementManager {
    final static Logger logger = Logger.getLogger(PlacementManager.class);
    private ServiceInstanceManager instance_manager;
    private ComputeMetrics c_metrics;
    final private PlacementConfig config;

    public PlacementManager() {
        this.instance_manager = new ServiceInstanceManager();
        config = PlacementConfigLoader.loadPlacementConfig();
        c_metrics = new ComputeMetrics();
    }

    public PlacementManager(ServiceInstance instance)
    {
        this.instance_manager = new ServiceInstanceManager();
        this.instance_manager.set_instance(instance);
        this.instance_manager.flush_chaining_rules();
        config = PlacementConfigLoader.loadPlacementConfig();
        c_metrics = new ComputeMetrics();

    }

    public ServiceInstance InitializeService(DeployServiceData serviceData)
    {
        return this.instance_manager.initialize_service_instance(serviceData);
    }

    public ServiceInstance GetServiceInstance()
    {
        return this.instance_manager.get_instance();
    }
    /**
     * This method returns the current Network Topology graph.
     * @return NetworkNode The root node of the topology graph.
     */
    public NetworkNode GenerateNetworkTopologyGraph()
    {
        logger.debug("PlacementManager::GenerateServiceGraph ENTER");
        String graph_text = "";

        NetworkTopologyGraph t_graph = new NetworkTopologyGraph();
        NetworkNode root = t_graph.generate_graph();
        if(null == root)
        {
            logger.error("PlacementManager::GenerateNetworkTopologyGraph: Topology Graph unavailable");
            logger.debug("PlacementManager::GenerateNetworkTopologyGraph EXIT");
            return null;
        }
        logger.debug("PlacementManager::GenerateNetworkTopologyGraph EXIT");
        return root;
    }

    /**
     * This method generates the service graph associated with the current Service Instance.
     * @return Node the root of the service graph.
     */
    public Node GenerateServiceGraph()
    {
        logger.debug("PlacementManager::GenerateServiceGraph ENTER");
        ServiceGraph graph = new ServiceGraph(instance_manager.get_instance());
        Node node = graph.generate_graph();

        if(node == null)
        {
            logger.error("PlacementManager::GenerateServiceGraph: Service Graph unavailable");
            logger.debug("PlacementManager::GenerateServiceGraph EXIT");
            return null;
        }
        logger.debug("PlacementManager::GenerateServiceGraph EXIT");
        return node;
    }

    /**
     * This method adds a link between two VNF instances.
     * @param SourceVnfInstance The Source VNF instance.
     * @param TargetVnfInstance The Target VNF instance.
     * @param ViaPath The customized routing path (Eg: tcpdump1-s1-s2-firwall1 viaPath = [s1,s2]).
     * @return Boolean Status of the link addition.
     */
    public boolean AddVirtualLink(String SourceVnfInstance, String TargetVnfInstance, List<String> ViaPath)
    {
        logger.debug("PlacementManager::AddVirtualLink ENTER");
        logger.info("PlacementManager::AddVirtualLink: Source VnfInstance: " + SourceVnfInstance
                + " Target VnfInstance: " + TargetVnfInstance);

        String SourceVnfId = this.instance_manager.get_instance().findVnfIdFromVnfInstanceName(SourceVnfInstance);

        if(SourceVnfId == null)
        {
            logger.fatal("PlacementManager::AddVirtualLink: Unable to add link to "
                    + SourceVnfInstance + ". Unknown VnfId or VNF does exist");
            logger.debug("PlacementManager::AddVirtualLink EXIT");
            return false;
        }

        String TargetVnfId = this.instance_manager.get_instance().findVnfIdFromVnfInstanceName(TargetVnfInstance);
        if(TargetVnfId == null)
        {
            logger.fatal("PlacementManager::AddVirtualLink: Unable to add link to "
                    + TargetVnfInstance + ". Unknown VnfId or VNF does not exist.");
            logger.debug("PlacementManager::AddVirtualLink EXIT");
            return false;
        }

        instance_manager.update_vlink_list(SourceVnfId, TargetVnfId, "vnf_" + SourceVnfInstance, "vnf_" + TargetVnfInstance, ViaPath,
                ServiceInstanceManager.ACTION_TYPE.ADD_INSTANCE);


        logger.debug("PlacementManager::AddVirtualLink EXIT");
        return true;
    }

    /**
     * This method deletes a link between two VNF instances.
     * @param SourceVnfInstance The Source VNF instance.
     * @param TargetVnfInstance The Target VNF instance.
     * @return Boolean Status of the link deletion.
     */
    public boolean DeleteVirtualLink(String SourceVnfInstance, String TargetVnfInstance)
    {
        logger.debug("PlacementManager::DeleteVirtualLink ENTRY");
        logger.info("PlacementManager::DeleteVirtualLink: Source VnfInstance: " + SourceVnfInstance
                + " Target VnfInstance: " + TargetVnfInstance);

        String SourceVnfId = this.instance_manager.get_instance().findVnfIdFromVnfInstanceName(SourceVnfInstance);
        if(SourceVnfId == null)
        {
            logger.fatal("PlacementManager::DeleteVirtualLink: Unable to delete link to "
                    + SourceVnfInstance + ". Unknown VnfId or VNF does not exist.");
            logger.debug("PlacementManager::DeleteVirtualLink EXIT");
            return false;
        }

        String TargetVnfId = this.instance_manager.get_instance().findVnfIdFromVnfInstanceName(TargetVnfInstance);
        if(TargetVnfId == null)
        {
            logger.fatal("PlacementManager::DeleteVirtualLink: Unable to delete link to "
                    + TargetVnfInstance + ". Unknown VnfId or VNF does not exist.");
            logger.debug("PlacementManager::DeleteVirtualLink EXIT");
            return false;
        }

        this.instance_manager.update_vlink_list(SourceVnfId, TargetVnfId, "vnf_" + SourceVnfInstance, "vnf_" + TargetVnfInstance, null,
                ServiceInstanceManager.ACTION_TYPE.DELETE_INSTANCE);
        logger.debug("PlacementManager::DeleteVirtualLink EXIT");
        return true;
    }

    /**
     * This method adds a new instance of a VNF.
     * @param VnfId String identifying the VNF ID if the VNF instance to be added.
     * @param PopName String identifying the PoP where the instance must be deployed.
     * @return String The name of the VNF instance.
     */
    public String AddNetworkFunctionInstance(String VnfId, String PopName)
    {
        logger.debug("PlacementManager::AddNetworkFunctionInstance ENTRY");
        logger.info("PlacementManager::AddNetworkFunctionInstance: VnfId: " + VnfId);

        if(!DatacenterManager.check_datacenter_exists(PopName))
        {
            logger.fatal("PlacementManager::AddNetworkFunctionInstance: Unknown datacenter: " + PopName);
            logger.debug("PlacementManager::AddNetworkFunctionInstance EXIT");
            return null;
        }

        String VnfInstanceName = this.instance_manager.update_functions_list(VnfId, null, PopName, ServiceInstanceManager.ACTION_TYPE.ADD_INSTANCE);
        if(VnfInstanceName == null)
        {
            logger.fatal("PlacementManager::AddNetworkFunctionInstance: Failed to add instance for Network Function " + VnfId);
            logger.debug("PlacementManager::AddNetworkFunctionInstance EXIT");
            return null;
        }
        logger.debug("PlacementManager::AddNetworkFunctionInstance EXIT");
        return VnfInstanceName;
    }

    /**
     * This method deletes an existing instance of a VNF.
     * @param VnfInstance String identifying the VNF instance to be deleted.
     * @return boolean Status of the VNF instance deletion.
     */
    public boolean DeleteNetworkFunctionInstance(String VnfInstance)
    {
        logger.debug("PlacementManager::DeleteNetworkFunctionInstance ENTRY");
        logger.info("PlacementManager::DeleteNetworkFunctionInstance: VnfInstance: " + VnfInstance);

        String VnfId = this.instance_manager.get_instance().findVnfIdFromVnfInstanceName(VnfInstance);
        if(VnfId != null)
            this.instance_manager.update_functions_list(VnfId, "vnf_" + VnfInstance, null, ServiceInstanceManager.ACTION_TYPE.DELETE_INSTANCE);
        else {
            logger.fatal("PlacementManager::DeleteNetworkFunctionInstance: Unable to delete function instance "
                    + VnfInstance + ". Vnf does not exist.");
            logger.debug("PlacementManager::DeleteNetworkFunctionInstance EXIT");
            return false;
        }

        logger.debug("PlacementManager::DeleteNetworkFunctionInstance EXIT");
        return true;
    }

    /**
     * This method migrates an existing instance of a VNF to another PoP.
     * @param VnfInstance String identifying the VNF instance that needs to be moved.
     * @param PopName String identifying the PoP to which the VNF instance has to be migrated.
     * @return boolean Status of the VNF instance migration.
     */
    public boolean MoveNetworkFunctionInstance(String VnfInstance, String PopName)
    {
        logger.debug("PlacementManager::MoveNetworkFunctionInstance ENTRY");
        logger.info("PlacementManager::MoveNetworkFunctionInstance: VnfInstance: " + VnfInstance + " PopName: " + PopName);

        if(!DatacenterManager.check_datacenter_exists(PopName))
        {
            logger.fatal("PlacementManager::MoveNetworkFunctionInstance: Unknown datacenter: " + PopName);
            logger.debug("PlacementManager::MoveNetworkFunctionInstance EXIT");
            return false;
        }

        String VnfId = this.instance_manager.get_instance().findVnfIdFromVnfInstanceName(VnfInstance);
        if(VnfId == null)
        {
            logger.fatal("PlacementManager::MoveNetworkFunctionInstance: Unknown Vnf instance.");
            logger.debug("PlacementManager::MoveNetworkFunctionInstance EXIT");
            return false;
        }

        boolean status = this.instance_manager.move_function_instance(VnfInstance, PopName);
        logger.debug("PlacementManager::MoveNetworkFunctionInstance EXIT");
        return status;

    }

    /**
     * This method returns the total available PoP's.
     * @return List String List of available PoP names.
     */
    public List<String> GetAvailablePoP()
    {
        logger.debug("PlacementManager::GetAvailablePoP ENTRY");
        List<String> PopList = new ArrayList<String>();

        ArrayList<PopResource> resource_list = config.getResources();
        for(PopResource resource : resource_list)
        {
            PopList.add(resource.getPopName());
        }
        logger.info("PlacementManager::GetAvailablePoP: Found " + PopList.size() + " PoP's");
        logger.debug("PlacementManager::GetAvailablePoP EXIT");
        return PopList;
    }

    /**
     * This method returns the total VNF instance capacity on the PoP.
     * @param PopName String identifying the PoP.
     * @return int Total VNF capacity of the PoP.

    public int GetTotalPoPCapacity(String PopName)
    {
        logger.debug("PlacementManager::GetTotalPoPCapacity ENTRY");
        logger.info("PlacementManager::GetTotalPoPCapacity: PopName: " + PopName);

        ArrayList<PopResource> resource_list = config.getResources();
        for(PopResource resource : resource_list)
        {
            if(resource.getPopName().equals(PopName)) {
                logger.info("PlacementManager::GetTotalPopCapacity: Capacity = " + resource.getNodes().size());
                logger.debug("PlacementManager::GetTotalPoPCapacity EXIT");
                return resource.getNodes().size();
            }
        }
        logger.error("PlacementManager::GetTotalPoPCapacity: Cannot find PoP: " + PopName);
        logger.debug("PlacementManager::GetTotalPoPCapacity EXIT");
        return 0;
    }*/

    /**
     * This method returns the total CPU capacity on the PoP.
     * @param PopName String identifying the PoP.
     * @return int Total CPU capacity of the PoP.
     */
    public double GetTotalCPU(String PopName)
    {
        logger.debug("PlacementManager::GetTotalCPU ENTRY");
        logger.info("PlacementManager::GetTotalCPU: PopName: " + PopName);

        if(!DatacenterManager.check_datacenter_exists(PopName))
        {
            logger.fatal("PlacementManager::GetTotalCPU: Unknown datacenter: " + PopName);
            logger.debug("PlacementManager::GetTotalCPU EXIT");
            return 0;
        }

        int cpu =  DatacenterManager.get_total_cpu(PopName);
        logger.debug("PlacementManager::GetTotalCPU EXIT");
        return cpu;
    }

    /**
     * This method returns the unused CPU capacity on the PoP.
     * @param PopName String identifying the PoP.
     * @return int Unused CPU capacity of the PoP.
     */
    public double GetAvailableCPU(String PopName)
    {
        logger.debug("PlacementManager::GetAvailableCPU ENTRY");
        logger.info("PlacementManager::GetAvailableCPU: PopName: " + PopName);

        if(!DatacenterManager.check_datacenter_exists(PopName))
        {
            logger.fatal("PlacementManager::GetAvailableCPU: Unknown datacenter: " + PopName);
            logger.debug("PlacementManager::GetAvailableCPU EXIT");
            return 0;
        }

        int cpu =  DatacenterManager.get_available_cpu(PopName);
        logger.debug("PlacementManager::GetAvailableCPU EXIT");
        return cpu;
    }

    /**
     * This method returns the available memory capacity on the PoP.
     * @param PopName String identifying the PoP.
     * @return int Unused memory capacity of the PoP.
     */
    public double GetAvailableMemory(String PopName)
    {
        logger.debug("PlacementManager::GetAvailableMemory ENTRY");
        logger.info("PlacementManager::GetAvailableMemory: PopName: " + PopName);

        if(!DatacenterManager.check_datacenter_exists(PopName))
        {
            logger.fatal("PlacementManager::GetAvailableMemory: Unknown datacenter: " + PopName);
            logger.debug("PlacementManager::GetAvailableMemory EXIT");
            return 0;
        }

        double memory =  DatacenterManager.get_available_memory(PopName);
        logger.debug("PlacementManager::GetAvailableMemory EXIT");
        return memory;
    }

    /**
     * This method returns the total memory capacity on the PoP.
     * @param PopName String identifying the PoP.
     * @return int Total memory capacity of the PoP.
     */
    public double GetTotalMemory(String PopName)
    {
        logger.debug("PlacementManager::GetTotalMemory ENTRY");
        logger.info("PlacementManager::GetTotalMemory: PopName: " + PopName);

        if(!DatacenterManager.check_datacenter_exists(PopName))
        {
            logger.fatal("PlacementManager::GetTotalMemory: Unknown datacenter: " + PopName);
            logger.debug("PlacementManager::GetTotalMemory EXIT");
            return 0;
        }

        double memory =  DatacenterManager.get_total_memory(PopName);
        logger.debug("PlacementManager::GetTotalMemory EXIT");
        return memory;
    }

    /**
     * This method returns the available storage capacity on the PoP.
     * @param PopName String identifying the PoP.
     * @return int Unused storage capacity of the PoP.
     */
    public double GetAvailableStorage(String PopName)
    {
        logger.debug("PlacementManager::GetAvailableStorage ENTRY");
        logger.info("PlacementManager::GetAvailableStorage: PopName: " + PopName);

        if(!DatacenterManager.check_datacenter_exists(PopName))
        {
            logger.fatal("PlacementManager::GetAvailableStorage: Unknown datacenter: " + PopName);
            logger.debug("PlacementManager::GetAvailableStorage EXIT");
            return 0;
        }

        double memory =  DatacenterManager.get_available_storage(PopName);
        logger.debug("PlacementManager::GetAvailableStorage EXIT");
        return memory;
    }

    /**
     * This method returns the total storage capacity on the PoP.
     * @param PopName String identifying the PoP.
     * @return int Total storage capacity of the PoP.
     */
    public double GetTotalStorage(String PopName)
    {
        logger.debug("PlacementManager::GetTotalStorage ENTRY");
        logger.info("PlacementManager::GetTotalStorage: PopName: " + PopName);

        if(!DatacenterManager.check_datacenter_exists(PopName))
        {
            logger.fatal("PlacementManager::GetTotalStorage: Unknown datacenter: " + PopName);
            logger.debug("PlacementManager::GetTotalStorage EXIT");
            return 0;
        }

        double storage =  DatacenterManager.get_total_storage(PopName);
        logger.debug("PlacementManager::GetTotalStorage EXIT");
        return storage;
    }


    /**
     * This method returns the overloaded VNFs.
     * @param message MonitorMessage Containing monitoring data.
     * @return List<String> List of overloaded VNFs.
     */
    public List<String> GetOverloadedVnfs(MonitorMessage message)
    {
        logger.debug("PlacementManager::GetOverloadedVnfs ENTRY");

        List<String> overload_l = new ArrayList<String>();
        List<String> underload_l = new ArrayList<String>();

        c_metrics.initialize(instance_manager.get_instance(), message.stats_history);
        c_metrics.compute_vnf_load(overload_l, underload_l);

        if(overload_l.size() == 0)
            logger.info("PlacementManager::GetOverloadedVnfs: No overloaded VNFs");

        logger.debug("PlacementManager::GetOverloadedVnfs EXIT");
        return overload_l;
    }

    /**
     * This method returns the underloaded VNFs.
     * @param message MonitorMessage Containing monitoring data.
     * @return List<String> List of underloaded VNFs.
     */
    public List<String> GetUnderloadedVnfs(MonitorMessage message)
    {
        logger.debug("PlacementManager::GetUnderloadedVnfs ENTRY");

        List<String> overload_l = new ArrayList<String>();
        List<String> underload_l = new ArrayList<String>();

        c_metrics.initialize(instance_manager.get_instance(), message.stats_history);
        c_metrics.compute_vnf_load(overload_l, underload_l);

        if(underload_l.size() == 0)
            logger.info("PlacementManager::GetOverloadedVnfs: No underloaded VNFs");

        logger.debug("PlacementManager::GetUnderloadedVnfs ENTRY");
        return underload_l;
    }

    /**
     * This method updates the performance threshold of a VNF Type.
     * @param VnfId VNF ID.
     * @param cpu_upper_l CPU upper limit.
     * @param cpu_lower_l CPU lower limit.
     * @param mem_upper_l Memory upper limit.
     * @param mem_lower_l Memory lower limit.
     * @param scale_out_upper_l Scale-out upper limit.
     * @param scale_in_lower_l Scale-in lower limit.
     * @param history_check Number of history data check.
     * @return void.
     */
    public void UpdatePerformanceThresholds(String VnfId,
                                            double cpu_upper_l,
                                            double cpu_lower_l,
                                            float mem_upper_l,
                                            float mem_lower_l,
                                            double scale_out_upper_l,
                                            double scale_in_lower_l,
                                            int history_check)
    {
        logger.debug("PlacementManager::UpdatePerformanceThresholds ENTRY");
        logger.info("PlacementManager::UpdatePerformanceThresholds: VNF ID: " + VnfId
                + "CPU Upper Limit: " + cpu_upper_l
                + "CPU Lower Limit: " + cpu_lower_l
                + "Memory Upper Limit: " + mem_upper_l
                + "Memory Lower Limit: " + mem_lower_l
                + "Scale-out Upper Limit: " + scale_out_upper_l
                + "Scale-in Lower Limit: " + scale_in_lower_l
                + "History Check: " + history_check);

        PerformanceThreshold threshold_new = new PerformanceThreshold();
        threshold_new.setCpu_lower_l(cpu_lower_l);
        threshold_new.setCpu_upper_l(cpu_upper_l);
        threshold_new.setMem_lower_l(mem_lower_l);
        threshold_new.setMem_upper_l(mem_upper_l);
        threshold_new.setScale_in_lower_l(scale_in_lower_l);
        threshold_new.setScale_out_upper_l(scale_out_upper_l);
        threshold_new.setVnfId(VnfId);
        threshold_new.setHistory_check(history_check);

        c_metrics.update_threshold(VnfId, threshold_new);

        logger.debug("PlacementManager::UpdatePerformanceThresholds EXIT");
        return;
    }
    /*
     * This method returns the free VNF instance capacity on the PoP.
     * @param PopName String identifying the PoP.
     * @return int Total VNF capacity of the PoP.
     *
    public int GetAvailablePoPCapacity(String PopName)
    {
        logger.debug("PlacementManager::GetAvailablePoPCapacity ENTRY");
        logger.info("PlacementManager::GetAvailablePoPCapacity: PopName: " + PopName);

        ArrayList<PopResource> resource_list = config.getResources();
        for(PopResource resource : resource_list)
        {

        }

        logger.error("PlacementManager::GetAvailablePoPCapacity: Cannot find PoP: " + PopName);
        logger.debug("PlacementManager::GetAvailablePoPCapacity EXIT");
        return 0;
    }*/


}
