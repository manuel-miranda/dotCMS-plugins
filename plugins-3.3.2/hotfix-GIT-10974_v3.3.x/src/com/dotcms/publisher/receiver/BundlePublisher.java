package com.dotcms.publisher.receiver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.apache.tools.tar.TarBuffer;

import com.dotcms.enterprise.LicenseUtil;
import com.dotcms.enterprise.publishing.remote.handler.*;
import com.dotcms.publisher.business.*;
import com.dotcms.publisher.business.PublisherAPIImpl;
import com.dotcms.publisher.pusher.PushPublisherConfig;
import com.dotcms.publisher.receiver.handler.IHandler;
import com.dotcms.publishing.*;
import com.dotcms.repackage.org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import com.dotcms.repackage.org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import com.dotcms.repackage.org.apache.commons.io.FileUtils;
import com.dotcms.repackage.org.apache.commons.lang.exception.ExceptionUtils;
import com.dotcms.rest.BundlePublisherResource;
import com.dotmarketing.db.HibernateUtil;
import com.dotmarketing.exception.DotHibernateException;
import com.dotmarketing.util.Config;
import com.dotmarketing.util.ConfigUtils;
import com.dotmarketing.util.Logger;
import com.dotmarketing.util.SecurityLogger;
import com.dotmarketing.util.UtilMethods;
import com.liferay.util.FileUtil;


public class BundlePublisher extends Publisher {

    private PublishAuditAPI auditAPI = null;
    boolean bundleSuccess = true;

    private List<IHandler> handlers = new ArrayList<IHandler>();

    /**
     * Initializes this Publisher adding all the handlers that can interact with a Bundle.
     *
     * @param config Class that have the main configuration values for the Bundle we are trying to publish
     * @return This bundle configuration ({@link PublisherConfig})
     * @throws DotPublishingException If fails initializing this Publisher Handlers
     */
    @Override
    public PublisherConfig init ( PublisherConfig config ) throws DotPublishingException {

        if ( LicenseUtil.getLevel() < 200 ) {
            throw new RuntimeException( "need an enterprise licence to run this" );
        }
        handlers = new ArrayList<IHandler>();
        handlers.add(new BundleXMLascHandler( config ));
        //The order is really important
        /**
         * ISSUE #2244: https://github.com/dotCMS/dotCMS/issues/2244
         *
         */
        handlers.add( new UserHandler( config ) );
        handlers.add( new CategoryHandler( config ) );
        handlers.add( new HostHandler( config ) );
        handlers.add( new FolderHandler( config ) );
        handlers.add( new WorkflowHandler( config ) );

        if ( Config.getBooleanProperty( "PUSH_PUBLISHING_PUSH_STRUCTURES" ) ) {
            handlers.add( new StructureHandler( config ) );
            /**
             * ISSUE #2222: https://github.com/dotCMS/dotCMS/issues/2222
             *
             */
            handlers.add( new RelationshipHandler( config ) );
        }

        handlers.add( new ContainerHandler( config ) );
        handlers.add( new TemplateHandler( config ) );
        handlers.add( new HTMLPageHandler( config ) );

        handlers.add( new LanguageHandler( config ) );
        handlers.add( new LanguageVariablesHandler( config ) );
        handlers.add( new ContentHandler( config ) );
        handlers.add( new ContentWorkflowHandler( config ) );
        handlers.add( new OSGIHandler( config ) );
        handlers.add( new LinkHandler( config ) );

        auditAPI = PublishAuditAPI.getInstance();

        this.config = super.init( config );
        return this.config;
    }

    /**
     * Processes a Bundle, in order to do that it: Un-compress the Bundle file, then each handler for this Publisher will check if inside<br/>
     * the bundle there is content it needs to be handle as each {@link IHandler Handler} handles a different type of content, and finally<br/>
     * after the "handle" for each Handler the status are set depending if was a successful operation or not.
     *
     * @param status Current status of the Publishing process
     * @return This bundle configuration ({@link PublisherConfig})
     * @throws DotPublishingException If fails Handling any on the elements of this bundle
     */
    @Override
    public PublisherConfig process ( final PublishStatus status ) throws DotPublishingException {
        if ( LicenseUtil.getLevel() < 300 ) {
            throw new RuntimeException( "need an enterprise licence to run this" );
        }

        String bundleName = config.getId();
        String bundleFolder = bundleName.substring( 0, bundleName.indexOf( ".tar.gz" ) );
        String bundlePath = ConfigUtils.getBundlePath() + File.separator + BundlePublisherResource.MY_TEMP;//FIXME

        //Publish the bundle extracted
        PublishAuditHistory currentStatusHistory = null;
        EndpointDetail detail = new EndpointDetail();

        try {
            //Update audit
            currentStatusHistory = auditAPI.getPublishAuditStatus( bundleFolder ).getStatusPojo();

            currentStatusHistory.setPublishStart( new Date() );
            detail.setStatus( PublishAuditStatus.Status.PUBLISHING_BUNDLE.getCode() );
            detail.setInfo( "Publishing bundle" );
            String endPointId = (String) currentStatusHistory.getEndpointsMap().keySet().toArray()[0];
            currentStatusHistory.addOrUpdateEndpoint(endPointId, endPointId, detail);

            auditAPI.updatePublishAuditStatus( bundleFolder, PublishAuditStatus.Status.PUBLISHING_BUNDLE, currentStatusHistory );
        } catch ( Exception e ) {
            Logger.error( BundlePublisher.class, "Unable to update audit table : " + e.getMessage(), e );
        }

        File folderOut = new File( bundlePath + bundleFolder );
        
        if(folderOut.exists()){
            FileUtil.deltree(folderOut);
        }
        folderOut.mkdir();

        // Extract file to a directory
        InputStream bundleIS;
        try {
            bundleIS = new FileInputStream( bundlePath + bundleName );
            untar( bundleIS, folderOut.getAbsolutePath() + File.separator + bundleName, bundleName );
        } catch ( FileNotFoundException e ) {
            throw new DotPublishingException( "Cannot extract the selected archive", e );
        }

        Map<String, String> assetsDetails = null;

        try {
            //Read the bundle to see what kind of configuration we need to apply
            String finalBundlePath = ConfigUtils.getBundlePath() + File.separator + bundleFolder;
            File xml = new File( finalBundlePath + File.separator + "bundle.xml" );
            PushPublisherConfig readConfig = (PushPublisherConfig) BundlerUtil.xmlToObject( xml );

            //Get the identifiers on this bundle
            assetsDetails = new HashMap<String, String>();
            List<PublishQueueElement> bundlerAssets = readConfig.getAssets();

            if ( bundlerAssets != null && !bundlerAssets.isEmpty() ) {
                for ( PublishQueueElement asset : bundlerAssets ) {
                    assetsDetails.put( asset.getAsset(), asset.getType() );
                }
            }
        } catch ( Exception e ) {
            Logger.error( BundlePublisher.class, "Unable to get assets list from received bundle: " + e.getMessage(), e );
        }

        try {
        	HibernateUtil.startTransaction();

            //Execute the handlers
            for ( IHandler handler : handlers ) {
            	
                handler.handle( folderOut );
                
            }
            
            HibernateUtil.commitTransaction();
        } catch ( Exception e ) {
            bundleSuccess = false;
            try {
                HibernateUtil.rollbackTransaction();
            } catch ( DotHibernateException e1 ) {
                Logger.error( PublisherAPIImpl.class, e.getMessage(), e1 );
            }
            Logger.error( PublisherAPIImpl.class, "Error Publishing Bundle: " + e.getMessage(), e );

            //Update audit
            try {
                detail.setStatus( PublishAuditStatus.Status.FAILED_TO_PUBLISH.getCode() );
                detail.setInfo( "Failed to publish because an error occurred: " + e.getMessage() );
                detail.setStackTrace( ExceptionUtils.getStackTrace( e ) );
                String endPointId = (String) currentStatusHistory.getEndpointsMap().keySet().toArray()[0];
                currentStatusHistory.addOrUpdateEndpoint(endPointId, endPointId, detail);
                currentStatusHistory.setBundleEnd( new Date() );
                currentStatusHistory.setAssets( assetsDetails );

                auditAPI.updatePublishAuditStatus( bundleFolder, PublishAuditStatus.Status.FAILED_TO_PUBLISH, currentStatusHistory );
            } catch ( DotPublisherException e1 ) {
                throw new DotPublishingException( "Cannot update audit: ", e );
            }
            throw new DotPublishingException( "Error Publishing: " + e, e );
        }

        try {
            //Update audit
            detail.setStatus( PublishAuditStatus.Status.SUCCESS.getCode() );
            detail.setInfo( "Everything ok" );
            String endPointId = (String) currentStatusHistory.getEndpointsMap().keySet().toArray()[0];
            currentStatusHistory.addOrUpdateEndpoint(endPointId, endPointId, detail);
            currentStatusHistory.setBundleEnd( new Date() );
            currentStatusHistory.setAssets( assetsDetails );
            auditAPI.updatePublishAuditStatus( bundleFolder, PublishAuditStatus.Status.SUCCESS, currentStatusHistory );
            HibernateUtil.commitTransaction();
        } catch ( Exception e ) {
            Logger.error( BundlePublisher.class, "Unable to update audit table : " + e.getMessage(), e );
        }

        try {
            HibernateUtil.closeSession();
        } catch ( DotHibernateException e ) {
            Logger.warn( this, e.getMessage(), e );
        }
        return config;
    }


    @SuppressWarnings ("rawtypes")
    @Override
    public List<Class> getBundlers () {
        List<Class> list = new ArrayList<Class>();

        return list;
    }

    /**
     * Untars a given tar bundle file in order process the content on it.
     *
     * @param bundle   Compressed Bundle file
     * @param path
     * @param fileName
     * @throws DotPublisherException 
     */
    private void untar ( InputStream bundle, String path, String fileName ) throws DotPublishingException  {
        TarArchiveEntry entry;
        TarArchiveInputStream inputStream = null;
        FileOutputStream outputStream = null;
        File baseBundlePath = new File(ConfigUtils.getBundlePath());
        
        try {
        	//Clean the bundler folder if exist to clean dirty data
        	String previousFolderPath = path.replace(fileName, "");
        	File previousFolder = new File(previousFolderPath);
        	if(previousFolder.exists()){
        		FileUtils.cleanDirectory(previousFolder);
        	}
            // get a stream to tar file
            InputStream gstream = new GZIPInputStream( bundle );
            inputStream = new TarArchiveInputStream(gstream, TarBuffer.DEFAULT_BLKSIZE, TarBuffer.DEFAULT_RCDSIZE,
                    UtilMethods.getCharsetConfiguration());

            // For each entry in the tar, extract and save the entry to the file
            // system
            while (null != (entry = inputStream.getNextTarEntry())) {
                // for each entry to be extracted
                int bytesRead;

                String pathWithoutName = path.substring(0, path.indexOf(fileName));
                File fileOrDir = new File(pathWithoutName + entry.getName());

                // if the logFile is outside of of the logFolder, die
                if ( !fileOrDir.getCanonicalPath().startsWith(baseBundlePath.getCanonicalPath())) {

                    SecurityLogger.logInfo(this.getClass(),  "Invalid Bundle writing file outside of bundlePath"  );
                    SecurityLogger.logInfo(this.getClass(),  " Bundle path "  + baseBundlePath );
                    SecurityLogger.logInfo(this.getClass(),  " Evil File "  + fileOrDir );
                    throw new DotPublishingException("Bundle trying to write outside of proper path:" + fileOrDir);
                }
                
                // if the entry is a directory, create the directory
                if ( entry.isDirectory() ) {
                    fileOrDir.mkdirs();
                    continue;
                }
                
                // We will ignore symlinks
                if(entry.isLink() || entry.isSymbolicLink()){
                    SecurityLogger.logInfo(this.getClass(),  "Invalid Bundle writing symlink (or some non-file) inside a bundle"  );
                    SecurityLogger.logInfo(this.getClass(),  " Bundle path "  + baseBundlePath );
                    SecurityLogger.logInfo(this.getClass(),  " Evil entry "  + entry );
                    throw new DotPublishingException("Bundle contains a symlink:" + fileOrDir);
                }

                // write to file
                byte[] buf = new byte[1024];
                outputStream = new FileOutputStream(pathWithoutName + entry.getName());
                while ( (bytesRead = inputStream.read( buf, 0, 1024 )) > -1 )
                    outputStream.write( buf, 0, bytesRead );
                try {
                    if ( null != outputStream ) {
                        outputStream.close();
                    }
                } catch ( Exception e ) {
                    Logger.warn( this.getClass(), "Error Closing Stream.", e );
                }
            }// while

        } catch ( Exception e ) {
            throw new DotPublishingException(e.getMessage(),e);
        } finally { // close your streams
            if ( inputStream != null ) {
                try {
                    inputStream.close();
                } catch ( IOException e ) {
                    Logger.warn( this.getClass(), "Error Closing Stream.", e );
                }
            }
            if ( outputStream != null ) {
                try {
                    outputStream.close();
                } catch ( IOException e ) {
                    Logger.warn( this.getClass(), "Error Closing Stream.", e );
                }
            }
        }
    }
}
