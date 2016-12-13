package sonata.kernel.placement;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import fi.iki.elonen.NanoHTTPD;
import org.apache.log4j.Logger;
import sonata.kernel.VimAdaptor.commons.vnfd.Unit;
import sonata.kernel.VimAdaptor.commons.vnfd.UnitDeserializer;
import sonata.kernel.placement.monitor.FunctionMonitor;
import sonata.kernel.placement.monitor.MonitorManager;
import sonata.kernel.placement.monitor.MonitorStats;
import sonata.kernel.placement.pd.PackageDescriptor;
import sonata.kernel.placement.pd.SonataPackage;
import sonata.kernel.placement.service.FunctionInstance;
import sonata.kernel.placement.service.LinkInstance;
import sonata.kernel.placement.service.ServiceInstance;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

public class MiniStatusServer {

    final static Logger logger = Logger.getLogger(MiniStatusServer.class);

    public static Path[] rootPaths = new Path[]{

            Paths.get("placement","YAML","static"),
            Paths.get("sandman","placement","YAML","static")
    };

    public static Path rootPath = null;
    public static String root = null;
    public static File rootFile = null;

    public static Map<String,String> extMimeMap = new HashMap<String,String>();

    static {

        for(Path rootP: rootPaths) {
            if (rootP.toFile().exists()) {
                rootPath = rootP;
                root = rootPath.toString();
                rootFile = rootPath.toFile();
            }
        }

        if (rootPath == null) {
            logger.error("No root path found");
        } else {
            logger.info("Found root path " + rootFile.getAbsolutePath());
        }

        extMimeMap.put("json","application/json");
        extMimeMap.put("js","application/javascript; charset=utf-8");
        extMimeMap.put("css","text/css");
        extMimeMap.put("htm","text/html");
        extMimeMap.put("html","text/html");

    }

    static final int maxHistory = 300;

    public static NanoHTTPD.Response serveDynamic(NanoHTTPD.IHTTPSession session){
        String uri = session.getUri();
        String json = null;
        Object jsonObj = null;


        if(uri.startsWith("/status"))
            uri = uri.substring(7);

        if(uri.equals("/status")) {

            Map<String,Object> statusObj = new HashMap<String, Object>();
            // Deployment status
            if(DeploymentManager.currentInstance == null) {
                statusObj.put("status", "UNDEPLOYED");
            } else {
                statusObj.put("status", "DEPLOYED");
            }
            // Catalogue package count
            statusObj.put("packageCount",Catalogue.packages.size());

            jsonObj = statusObj;
        }

        if(uri.equals("/monitor")) {
            if(DeploymentManager.currentInstance != null) {
                Map<String,Object> statusObj = new HashMap<String, Object>();
                //Map<String,String> functionMap = new HashMap<String,String>();
                // Deployed instance monitor information
                MessageQueue.MessageQueueMonitorData monitorData = MonitorManager.getMonitorData();
                statusObj.put("monitorFunctions", monitorData.statsHistoryMap.keySet());
                // Clear unnecessary data
                for(List<MonitorStats> list : monitorData.statsHistoryMap.values()){
                    if(list.size()>maxHistory) {
                        for(int i=list.size()-maxHistory-1; i>=0; i--)
                            list.remove(i);
                    }
                }
                /*
                for(String key:monitorData.statsMap.keySet()) {
                    functionMap.put(key, key.substring(0,key.lastIndexOf(":")));
                }
                */
                // function instance list
                // templateName ->
                //      instanceName
                //      vnfId
                //      vnfName
                //

                // Key of nodeMap is normal name like firewall2
                Map<String,Map<String,String>> nodeMap = new HashMap<String,Map<String,String>>();
                // Key of instanceMap is template name like firewall2:asdfasdfadsf
                Map<String,Map<String,String>> instanceMap = new HashMap<String,Map<String,String>>();
                List<FunctionMonitor> monitors = MonitorManager.getMonitorListCopy();
                for(FunctionMonitor monitor: monitors) {
                    if(monitor == null)
                        System.out.println("");
                    Map<String,String> fMap = new HashMap<String,String>();
                    fMap.put("instanceName", monitor.instance.name);
                    fMap.put("vnfId", monitor.instance.function.getVnfId());
                    fMap.put("vnfName", monitor.instance.descriptor.getName());
                    instanceMap.put(monitor.function, fMap);
                    nodeMap.put(monitor.instance.name, fMap);
                }
                // instance graph
                List<String> nsPoints = new ArrayList<String>();
                List<List<String>> nsPointToNode = new ArrayList<List<String>>();
                //Map<String,String> nsPointToNode = new HashMap<String,String>();
                List<List<String>> nodeToNode = new ArrayList<List<String>>();
                //Map<String,String> nodeToNode = new HashMap<String,String>();
                ServiceInstance serviceInstance = DeploymentManager.currentInstance;

                // Get nsPoints and nsPointToNode links
                for(Map.Entry<String,Map<String, LinkInstance>> linkMap : serviceInstance.outerlink_list.entrySet()) {
                    String nsPointName = linkMap.getKey();
                    for(Map.Entry<String, LinkInstance> link : linkMap.getValue().entrySet()) {
                        Set<FunctionInstance> functions = link.getValue().interfaceList.keySet();
                        for(FunctionInstance f: functions) {
                            //nsPointToNode.put(nsPointName, f.name);
                            List<String> linkPair = new ArrayList<String>();
                            linkPair.add(nsPointName);
                            linkPair.add(f.name);
                            nsPointToNode.add(linkPair);
                        }
                    }
                    nsPoints.add(nsPointName);
                }

                // Get nodeToNode links
                for(Map.Entry<String,Map<String, LinkInstance>> linkMap : serviceInstance.innerlink_list.entrySet()) {
                    for(Map.Entry<String, LinkInstance> link : linkMap.getValue().entrySet()) {
                        // Assume one to one connections
                        List<FunctionInstance> functions = new ArrayList<FunctionInstance>(link.getValue().interfaceList.keySet());
                        //nodeToNode.put(functions.get(0).name, functions.get(1).name);
                        List<String> linkPair = new ArrayList<String>();
                        linkPair.add(functions.get(0).name);
                        linkPair.add(functions.get(1).name);
                        nodeToNode.add(linkPair);
                    }
                }
                Map<String,Object> graph = new HashMap<String,Object>();
                graph.put("nsPoints",nsPoints);
                graph.put("nodes",nodeMap);
                graph.put("nsPointToNode",nsPointToNode);
                graph.put("nodeToNode",nodeToNode);
                graph.put("forwardGraphs",serviceInstance.service.getForwardingGraphs());
                statusObj.put("graph",graph);

                statusObj.put("thresholds",PlacementConfigLoader.loadPlacementConfig().getThreshold());

                //statusObj.put("names",functionMap);
                statusObj.put("functions", instanceMap);
                //statusObj.put("monitorLastData", monitorData.statsMap);
                statusObj.put("monitorHistoryData", monitorData.statsHistoryMap);

                jsonObj = statusObj;
            }
        }

        if(uri.equals("/packages")) {
            List<PackageDescriptor> packageList = new ArrayList<PackageDescriptor>();
            for(SonataPackage p:Catalogue.packages)
                packageList.add(p.descriptor);
            jsonObj = packageList;
        }

        try {
            if(jsonObj != null) {
                json = jsonMapper.writeValueAsString(jsonObj);
                //logger.debug(json);
                logger.debug("200 - "+uri);
                return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", json);
            }
        } catch (JsonProcessingException e) {
        }

        logger.debug("404 - "+uri);
        return newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, null, null);
    }

    public static NanoHTTPD.Response serveStatic(NanoHTTPD.IHTTPSession session) {

        String uri = session.getUri();

        if (rootPath == null) {
            logger.debug("404 - "+uri);
            return newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, null, null);
        }

        if(uri.startsWith("/static"))
            uri = uri.substring(7);

        File requestFile = searchForFile(uri);
        if (requestFile != null) {

            String[] fileParts = requestFile.getName().split("\\.");
            String ext = fileParts.length>0 ? fileParts[fileParts.length-1] : "";
            String mime = extMimeMap.get(ext);
            if (mime == null)
                mime = "pext/plain";

            FileInputStream fis = null;
            try {
                fis = new FileInputStream(requestFile);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                logger.error(e);
            }
            if (fis != null) {
                logger.debug("200 - "+uri);
                return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, mime, fis, requestFile.length());
            }
        }

        logger.debug("404 - "+uri);
        return newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, null, null);
    }

    public static File searchForFile(String file){
        File requestFile;
        File rootDirectory;
        try {
            requestFile = Paths.get(root, file).toFile().getCanonicalFile();
            rootDirectory = rootFile.getCanonicalFile();
            //logger.debug("Request "+requestFile);
            //logger.debug("Root "+rootDirectory);
            if (requestFile.exists() && requestFile.getParentFile().equals(rootDirectory)) {
                return requestFile;
            }
        } catch (IOException e) {
            e.printStackTrace();
            logger.error(e);
        }
        return null;
    }

    static ObjectMapper jsonMapper;

    static {
        jsonMapper = new ObjectMapper(new JsonFactory());
        jsonMapper.disable(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS);
        jsonMapper.enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING);
        jsonMapper.disable(SerializationFeature.WRITE_NULL_MAP_VALUES);
        jsonMapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        jsonMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        SimpleModule module = new SimpleModule();
        module.addDeserializer(Unit.class, new UnitDeserializer());
        jsonMapper.registerModule(module);
        jsonMapper.enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);
    }

}