package sonata.kernel.placement.pd;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.apache.commons.io.IOUtils;
import sonata.kernel.VimAdaptor.commons.DeployServiceData;
import sonata.kernel.VimAdaptor.commons.nsd.ServiceDescriptor;
import sonata.kernel.VimAdaptor.commons.vnfd.Unit;
import sonata.kernel.VimAdaptor.commons.vnfd.UnitDeserializer;
import sonata.kernel.VimAdaptor.commons.vnfd.VnfDescriptor;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.log4j.Logger;

/**
 * Utility functions to extract a Sonata package
 */
public class PackageLoader {

	final static Logger logger = Logger.getLogger(PackageLoader.class);
    /**
     * Path to save debug files from extracted Sonata packages to.
     */
    public final static String basedir = Paths.get(System.getProperty("java.io.tmpdir"), "placementtmp").toString();

    /**
     * Extracts a Sonata package and saves contents to disk.
     * @param data Sonata package as byte[]
     * @return Path to the directory containing the extracted package
     * @throws IOException
     */
    public static String processZipFile(byte[] data) throws IOException {
    	logger.debug("Processing ZIP file");
        // Create destination paths
        String currentDir = "";
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd-HHmmss");
        currentDir = Paths.get(basedir,sdf.format(new Date())).toString();
        logger.debug("Current Directory: "+ currentDir);
        String sddirstr = Paths.get(currentDir,"sd").toString();
        String vnfddirstr = Paths.get(currentDir,"vnfd").toString();
        logger.debug("Service descriptor Dir: "+sddirstr);
        logger.debug("VNF descriptor Dir: "+vnfddirstr);
        // Create directories if necessary
        File sddir = new File(sddirstr);
        if(sddir.isDirectory()==false)
            sddir.mkdirs();
        File vnfddir = new File(vnfddirstr);
        if(vnfddir.isDirectory()==false)
            vnfddir.mkdirs();

        // Write whole package to disk
        FileOutputStream datastream = new FileOutputStream(Paths.get(currentDir,"data").toString());
        datastream.write(data);
        datastream.close();


        List<byte[]> services = new ArrayList<byte[]>();
        List<byte[]> functions = new ArrayList<byte[]>();

        processZipFileData(data, services, functions);

        int i;
        for(i=0; i<services.size(); i++){
            FileOutputStream foutstream = new FileOutputStream(Paths.get(sddirstr,"ns"+i+".yml").toString());
            foutstream.write(services.get(i));
            foutstream.close();
        }

        for(i=0; i<functions.size(); i++){
            FileOutputStream foutstream = new FileOutputStream(Paths.get(vnfddirstr,"vnfd"+i+".yml").toString());
            foutstream.write(functions.get(i));
            foutstream.close();
        }

        return currentDir;
    }

    /**
     * Loads a Sonata package from disk and extracts it.
     * @param packagePath Path to the Sonata package
     * @return Contents of the Sonata package
     */
    public static DeployServiceData loadPackageFromDisk(String packagePath) {
    	logger.debug("Loading package from disk");
        File packageFile = new File(packagePath);
        if (!packageFile.exists())
            return null;

        byte[] data;
        DeployServiceData serviceData = new DeployServiceData();

        try {

            data = Files.readAllBytes(Paths.get(packagePath));

            SonataPackage pack = zipByteArrayToSonataPackage(data);

            serviceData.setServiceDescriptor(pack.services.get(0));
            for(VnfDescriptor function : pack.functions)
                serviceData.addVnfDescriptor(function);

            return serviceData;

        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    /**
     * Loads a Sonata package from disk and extracts it.
     * @param packagePath Path to the Sonata package
     * @return Contents of the Sonata package
     */
    public static SonataPackage loadSonataPackageFromDisk(String packagePath) {
        logger.debug("Loading sonata package from disk");
        File packageFile = new File(packagePath);
        if (!packageFile.exists())
            return null;

        byte[] data;

        try {

            data = Files.readAllBytes(Paths.get(packagePath));

            SonataPackage pack = zipByteArrayToSonataPackage(data);

            return pack;

        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    /**
     * Extracts Sonata package from a byte[]
     * @param data Sonata package as byte[]
     * @return Sonata package object
     */
    public static SonataPackage zipByteArrayToSonataPackage(byte[] data){

        List<byte[]> servicesDataList = new ArrayList<byte[]>();
        List<byte[]> functionsDataList = new ArrayList<byte[]>();

        List<ServiceDescriptor> services = new ArrayList<ServiceDescriptor>();
        List<VnfDescriptor> functions = new ArrayList<VnfDescriptor>();
        SonataPackage pack = new SonataPackage();
        PackageDescriptor pd = null;

        try {
            pd = processZipFileData(data, servicesDataList, functionsDataList);
        } catch (IOException e) {
            e.printStackTrace();
        }

        for(byte[] serviceBytes : servicesDataList) {
            try {
                services.add(byteArrayToServiceDescriptor(serviceBytes));
            } catch (Exception e){
                e.printStackTrace();
                pack.validation.exceptions.add(e);
            }
        }

        for(byte[] functionBytes : functionsDataList) {
            try {
                functions.add(byteArrayToVnfDescriptor(functionBytes));
            } catch (Exception e){
                e.printStackTrace();
                pack.validation.exceptions.add(e);
            }
        }

        if(pd == null)
            return null;

        pack.descriptor = pd;
        pack.functions.addAll(functions);
        pack.services.addAll(services);
        return pack;
    }

    /**
     * Maps a byte[] to a ServiceDescriptor object
     * @param data ServiceDescriptor as byte[]
     * @return ServiceDescriptor object
     * @throws Exception
     */
    public static ServiceDescriptor byteArrayToServiceDescriptor(byte[] data) throws Exception {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        SimpleModule module = new SimpleModule();

        ServiceDescriptor sd;


        StringBuilder bodyBuilder = new StringBuilder();
        BufferedReader in = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(data), Charset.forName("UTF-8")));
        String line;

        while ((line = in.readLine()) != null)
            bodyBuilder.append(line + "\n\r");

        module.addDeserializer(Unit.class, new UnitDeserializer());
        mapper.registerModule(module);
        mapper.enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);
        sd = mapper.readValue(bodyBuilder.toString(), ServiceDescriptor.class);


        return sd;
    }

    /**
     * Reads in a file and maps it to a VnfDescriptor
     * @param vnfFile File referencing a VnfDescriptor
     * @return
     */
    public static VnfDescriptor fileToVnfDescriptor(File vnfFile){
        try {
            return byteArrayToVnfDescriptor(IOUtils.toByteArray(new FileInputStream(vnfFile)));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Maps a byte[] to a VnfDescriptor
     * @param data VnfDescriptor as byte[]
     * @return
     * @throws Exception
     */
    public static VnfDescriptor byteArrayToVnfDescriptor(byte[] data) throws Exception {
    	logger.debug("Byte array to VNF descriptor");
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        SimpleModule module = new SimpleModule();

        VnfDescriptor vnfd;

        StringBuilder bodyBuilder = new StringBuilder();
        BufferedReader in = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(data), Charset.forName("UTF-8")));
        String line = null;
        while ((line = in.readLine()) != null)
            bodyBuilder.append(line + "\n\r");

        module.addDeserializer(Unit.class, new UnitDeserializer());
        mapper.registerModule(module);
        mapper.enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);
        vnfd = mapper.readValue(bodyBuilder.toString(), VnfDescriptor.class);

        return vnfd;
    }

    /**
     * Extracts a Sonata package to binary Service and Vnf descriptors
     * @param data Sonata package as byte[]
     * @param services List for binary Service descriptors to be filled
     * @param functions List for binary Vnf descriptors to be filled
     * @return Contents of the Sonata package mapped to an PackageDescriptor object
     * @throws IOException
     */
    public static PackageDescriptor processZipFileData(byte[] data, List<byte[]> services, List<byte[]> functions) throws IOException {
    	
    	logger.debug("Processing zip file data");
        ByteArrayInputStream byteIn = new ByteArrayInputStream(data);

        ZipInputStream zipstream;
        zipstream = new ZipInputStream(byteIn);
        ZipEntry ze = zipstream.getNextEntry();

        // Extract files to byte[] and save them in fileMap
        Map<String, byte[]> fileMap = new HashMap<String, byte[]>();
        while (ze != null) {

            if(ze.isDirectory()!=true) {

                byte[] fileData = readFile(zipstream, ze);
                String filePath = ze.getName();
                fileMap.put(filePath, fileData);
                logger.debug("File data length: "+ fileData.length);
                logger.debug("File Path: "+ filePath);

            }

            ze = zipstream.getNextEntry();
        }
        zipstream.closeEntry();
        zipstream.close();

        logger.debug("End of ZIP file");
        Set<String> files = fileMap.keySet();
        PackageDescriptor pd = null;

        if (files.contains("META-INF/MANIFEST.MF")) {



            byte[] pfile = fileMap.get("META-INF/MANIFEST.MF");

            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            SimpleModule module = new SimpleModule();
            //module.addDeserializer(Unit.class, new UnitDeserializer());
            mapper.registerModule(module);
            mapper.enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);

            StringBuilder bodyBuilder = new StringBuilder();
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(pfile), Charset.forName("UTF-8")));
            String line;
            while ((line = in.readLine()) != null)
                bodyBuilder.append(line + "\n\r");

            pd = mapper.readValue(bodyBuilder.toString(), PackageDescriptor.class);



            MessageDigest md5 = null;
            try {
                md5 = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }



            for(PackageContentObject pObj:pd.getPackageContent()){

                String name;
                name = pObj.getName();

                if (name==null)
                    continue;

                // Remove leading "/" from file path
                if (name.charAt(0)=='/')
                    name = name.substring(1);

                logger.debug("Content file name: "+ name);
                // Get file data from map
                byte[] fileData;
                fileData = fileMap.get(name);
                if (fileData==null) {
                    logger.debug("No data found in: "+ name);
                    continue;
                }

                // Check MD5
                if (md5!=null) {
                    byte[] digest = md5.digest(fileData);
                    
                    digest.toString();
                    BigInteger bigInt = new BigInteger(1,digest);
                    String hashtext = bigInt.toString(16);
                    // Now we need to zero pad it if you actually want the full 32 chars.
                    while(hashtext.length() < 32 ){
                        hashtext = "0"+hashtext;
                    }
                    String fileMd5 = pObj.getMd5();
                    if (fileMd5!=null) {
                        if (hashtext.toLowerCase().equals(fileMd5.toLowerCase()) == false) {
                            logger.debug("MD5 mismatch for file: "+ name+" (given md5: "+fileMd5.toLowerCase()+", actual md5: "+hashtext.toLowerCase()+")");
                        } else {
                            logger.debug("MD5 matches "+hashtext.toLowerCase());
                        }
                    }
                }

                logger.debug("ContentType: "+pObj.getContentType());

                // It's a service descriptor
                if (pObj.getContentType()!= null && pObj.getContentType().startsWith("application/sonata.service_descriptor")) {

                    services.add(fileData);
                    logger.debug("Service Descriptor found: "+name);
                }

                // It's a function descriptor
                if (pObj.getContentType() != null && pObj.getContentType().startsWith("application/sonata.function_descriptor")) {

                    functions.add(fileData);
                    logger.debug("Function Descriptor found: "+name);
                }
            }
        }
        return pd;
    }

    /**
     * Turns a ZipEntry to a byte[]
     * @param zipstream Contains the file
     * @param ze Describes the file entry
     * @return The zipped file as byt[]
     * @throws IOException
     */
    public static byte[] readFile(ZipInputStream zipstream, ZipEntry ze) throws IOException {
        long size = ze.getSize();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int read = 0;
        while(size>0) {
            read = zipstream.read(buf, 0, 2048);
            outputStream.write(buf, 0, read);
            size -= read;
        }
        return outputStream.toByteArray();
    }
}
