package org.neuinfo.foundry.consumers.jms.consumers;

import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import org.apache.log4j.Logger;
import org.bson.types.ObjectId;
import org.json.JSONObject;
import org.neuinfo.foundry.common.ingestion.DocumentIngestionService;
import org.neuinfo.foundry.common.ingestion.GridFSService;
import org.neuinfo.foundry.common.model.DocWrapper;
import org.neuinfo.foundry.common.model.PrimaryKeyDef;
import org.neuinfo.foundry.common.model.Source;
import org.neuinfo.foundry.common.util.Assertion;
import org.neuinfo.foundry.common.util.JSONUtils;
import org.neuinfo.foundry.common.util.Utils;
import org.neuinfo.foundry.consumers.common.ConsumerProcessListener;
import org.neuinfo.foundry.consumers.common.IDGenerator;
import org.neuinfo.foundry.consumers.common.ServiceFactory;
import org.neuinfo.foundry.consumers.jms.consumers.plugins.ProvenanceHelper;
import org.neuinfo.foundry.consumers.jms.consumers.plugins.ProvenanceHelper.ProvData;
import org.neuinfo.foundry.consumers.plugin.*;

import javax.jms.JMSException;
import javax.jms.MessageListener;
import java.util.Date;

/**
 * Created by bozyurt on 10/28/14.
 */
public class GenericIngestionConsumer extends ConsumerSupport implements Ingestable {
    private Ingestor ingestor;
    private String id;
    private String name;
    private ConsumerProcessListener cpListener;
    private final static Logger logger = Logger.getLogger(GenericIngestionConsumer.class);

    public GenericIngestionConsumer(String queueName) {
        super(queueName);
    }

    public void setCpListener(ConsumerProcessListener cpListener) {
        this.cpListener = cpListener;
    }

    @Override
    public void startup(String configFile) throws Exception {
        super.startup(configFile);
        ServiceFactory.getInstance(configFile);
    }

    @Override
    public String getId() {
        if (id == null) {
            id = String.valueOf(IDGenerator.getInstance().getNextId());
        }
        return id;
    }

    @Override
    public String getName() {
        if (name == null && ingestor != null) {
            name = ingestor.getName();
        }
        return name;
    }

    @Override
    public void handleMessages(MessageListener listener) throws JMSException {
        try {
            handle();
        } catch (Exception x) {
            x.printStackTrace();
        }
    }

    void handle() throws Exception {
        String srcNifId = ingestor.getOption("srcNifId");
        String dataSource = ingestor.getOption("dataSource");
        String batchId = ingestor.getOption("batchId");
        String includeFile = ingestor.getOption("includeFile");
        boolean onlyErrors = ingestor.getOption("onlyErrors") != null ? Boolean.parseBoolean(ingestor.getOption("onlyErrors")) : false;

        String docUpdaterImplClass = ingestor.getOption("docUpdaterImplClass");
        IDocUpdater docUpdater = null;


        DocumentIngestionService dis = new DocumentIngestionService();
        GridFSService gridFSService = new GridFSService();
        MessagePublisher messagePublisher = null;
        try {
            dis.start(this.config);
            gridFSService.start(this.config);
            messagePublisher = new MessagePublisher(this.config.getBrokerURL());

            Source source = dis.findSource(srcNifId, dataSource);
            Assertion.assertNotNull(source, "Cannot find source for sourceID:" + srcNifId);
            dis.setSource(source);

            if (docUpdaterImplClass != null) {
                Class<?> clazz = Class.forName(docUpdaterImplClass);
                docUpdater = (IDocUpdater) clazz.getConstructor(MongoClient.class, String.class, String.class, String.class,
                        MessagePublisher.class, String.class).newInstance(this.mongoClient, this.config.getMongoDBName(),
                        this.config.getCollectionName(), srcNifId, messagePublisher, getOutStatus());

            }

            int submittedCount = 0;
            int ingestedCount = 0;
            int updatedCount = 0;
            int newCount = 0;
            dis.beginBatch(source, batchId);
            ingestor.startup();

            String theCollection = getCollectionName();
            if (!Utils.isEmpty(source.getCollectionName())) {
                theCollection = source.getCollectionName();
            }

            while (ingestor.hasNext()) {
                try {
                    Date startDate = new Date();
                    Result result = ingestor.prepPayload();
                    logger.info("ingesting record " + submittedCount);
                    if (result.getStatus() == Result.Status.OK_WITH_CHANGE) {
                        BasicDBObject document = dis.findDocument(result.getPayload(), theCollection);
                        if (document != null) {
                            String updateOutStatus = getIngestor().getOption("updateOutStatus");
                            Assertion.assertNotNull(updateOutStatus);
                            // find difference and update OriginalDoc
                            BasicDBObject origDocDBO = (BasicDBObject) document.get("OriginalDoc");
                            JSONObject origDocJS = JSONUtils.toJSON(origDocDBO, false);
                            JSONObject payload = result.getPayload();

                            DBObject pi = (DBObject) document.get("Processing");
                            boolean contentSame = false;
                            if (!onlyErrors) {
                                if (result.isLargeRecord()) {
                                    String oldObjectIdStr = (String) document.get("originalFileId");
                                    Assertion.assertNotNull(oldObjectIdStr);
                                    contentSame = gridFSService.isSame(payload, new ObjectId(oldObjectIdStr));
                                } else {
                                    contentSame = JSONUtils.isEqual(origDocJS, payload);
                                }
                            }
                            String status = (String) pi.get("status");
                            boolean needsReprocess = (onlyErrors && (status != null && status.equals("error"))) ||
                                    includeFile != null || (status != null && (status.equals("error") || !status.equals("finished")));
                            if (onlyErrors || contentSame) {
                                if (needsReprocess) {
                                    // the previous doc processing ended with error,
                                    // or doc needs to be reprocessed, so start over
                                    ObjectId originalFileId = null;
                                    JSONObject newPayload = result.getPayload();
                                    if (result.isLargeRecord()) {
                                        String oldObjectIdStr = (String) document.get("originalFileId");
                                        Assertion.assertNotNull(oldObjectIdStr);
                                        gridFSService.deleteJSONFile(new ObjectId(oldObjectIdStr));
                                        originalFileId = gridFSService.saveJsonFile(newPayload,
                                                result.getCachedFileName());
                                        newPayload = new JSONObject();
                                    }
                                    DocWrapper dw = dis.prepareDocWrapper(newPayload, originalFileId,
                                            batchId, source, getOutStatus());

                                    // save provenance
                                    ProvData provData = new ProvData(dw.getPrimaryKey(),
                                            ProvenanceHelper.ModificationType.Ingested);
                                    provData.setSourceName(dw.getSourceName()).setSrcId(dw.getSourceId());

                                    // first cleanup any previous provenance data
                                    ProvenanceHelper.removeProvenance(dw.getPrimaryKey());
                                    ProvenanceHelper.saveIngestionProvenance("ingestion",
                                            provData, startDate, dw);
                                    // delete previous record first
                                    dis.removeDocument(document, theCollection);
                                    ObjectId oid = dis.saveDocument(dw, theCollection);
                                    if (result.getStatus() == Result.Status.ERROR) {
                                        dis.incrErrorCount(source);
                                    } else {
                                        dis.incrUpdatedCount(source);
                                    }
                                    updatedCount++;
                                    messagePublisher.sendMessage(oid.toString(), getOutStatus(), theCollection);
                                }
                            } else {
                                ObjectId originalFileId;
                                if (result.isLargeRecord()) {
                                    String oldObjectIdStr = (String) document.get("originalFileId");
                                    Assertion.assertNotNull(oldObjectIdStr);
                                    gridFSService.deleteJSONFile(new ObjectId(oldObjectIdStr));
                                    originalFileId = gridFSService.saveJsonFile(result.getPayload(),
                                            result.getCachedFileName());
                                    document.put("originalFileId", originalFileId.toString());
                                } else {
                                    DBObject dbObject = JSONUtils.encode(payload, true);
                                    document.put("OriginalDoc", dbObject);
                                }// update CrawlDate
                                document.put("CrawlDate", JSONUtils.toBsonDate(new Date()));
                                pi.put("status", updateOutStatus);
                                updatedCount++;
                                dis.updateDocument(document, theCollection, batchId);
                                if (result.getStatus() == Result.Status.ERROR) {
                                    dis.incrErrorCount(source);
                                } else {
                                    dis.incrUpdatedCount(source);
                                }
                                String oidStr = document.get("_id").toString();
                                //
                                messagePublisher.sendMessage(oidStr, updateOutStatus, theCollection);
                            }
                        } else {
                            ObjectId originalFileId = null;
                            JSONObject payload = result.getPayload();
                            DocWrapper dw;
                            if (result.isLargeRecord()) {
                                PrimaryKeyDef pkDef = source.getPrimaryKeyDef();
                                String primaryKey = pkDef.prepPrimaryKey(payload);
                                originalFileId = gridFSService.saveJsonFile(result.getPayload(),
                                        result.getCachedFileName());
                                payload = new JSONObject();
                                DocWrapper.Builder builder = new DocWrapper.Builder(getOutStatus());
                                dw = builder.batchId(batchId).payload(payload).version(1)
                                        .crawlDate(new Date()).sourceId(source.getResourceID())
                                        .sourceName(source.getName()).primaryKey(primaryKey)
                                        .dataSource(source.getDataSource()).origFileId(originalFileId).build();
                            } else {
                                dw = dis.prepareDocWrapper(payload, originalFileId,
                                        batchId, source, getOutStatus());
                            }
                            // save provenance
                            ProvData provData = new ProvData(dw.getPrimaryKey(),
                                    ProvenanceHelper.ModificationType.Ingested);
                            provData.setSourceName(dw.getSourceName()).setSrcId(dw.getSourceId());
                            // first cleanup any previous provenance data
                            ProvenanceHelper.removeProvenance(dw.getPrimaryKey());
                            ProvenanceHelper.saveIngestionProvenance("ingestion",
                                    provData, startDate, dw);
                            ObjectId oid = dis.saveDocument(dw, theCollection);
                            newCount++;
                            if (result.getStatus() == Result.Status.ERROR) {
                                dis.incrErrorCount(source);
                            } else {
                                dis.incrNewCount(source);
                            }
                            messagePublisher.sendMessage(oid.toString(), getOutStatus(), theCollection);
                        }
                        ingestedCount++;
                    }
                } catch (Throwable t) {
                    logger.error("handle", t);
                    t.printStackTrace();
                    dis.incrErrorCount(source);
                } finally {
                    submittedCount++;
                }
            }
            if (docUpdater != null) {
                ((IngestorLifeCycle) ingestor).beforeShutdown(docUpdater);
            }

            dis.endBatch(source, batchId, ingestedCount, submittedCount, updatedCount, newCount);
        } finally {
            dis.shutdown();
            ingestor.shutdown();
            gridFSService.shutdown();
            if (messagePublisher != null) {
                messagePublisher.close();
            }
        }
    }

    @Override
    public void setIngestor(Ingestor ingestor) {
        this.ingestor = ingestor;
    }

    @Override
    public Ingestor getIngestor() {
        return this.ingestor;
    }


}
