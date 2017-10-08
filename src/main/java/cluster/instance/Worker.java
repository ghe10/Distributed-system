package cluster.instance;

import cluster.util.WorkerInstanceModel;
import cluster.util.WorkerReceiver;
import cluster.util.WorkerSender;
import network.datamodel.*;
import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;
import scheduler.FileSystemScheduler;
import usertool.Constants;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

public class Worker extends BasicWatcher {
    private String serverId;
    private String status;
    private String name;
    private String myIp;
    private WorkerSender workerSender;
    private WorkerReceiver workerReceiver;
    private FileSystemScheduler fileSystemScheduler;
    private LinkedList<CommunicationDataModel> comDataList;
    private LinkedList<Object> communicationSendQueue;
    private LinkedList<Object> fileSystemObjectQueue;
    private Hashtable<String, FileStorageLocalDataModel> fileStorageInfo;
    private WorkerThread worker;
    private Thread workerThread;

    public Worker(String hostPort, String serverId, int sessionTimeOut,
                  WorkerSender workerSender, WorkerReceiver workerReceiver) throws UnknownHostException {
        super(hostPort, sessionTimeOut);
        Random random = new Random();
        this.serverId = serverId != null ? serverId : String.valueOf(random.nextInt());
        this.name = String.format("worker-%s", serverId);
        this.workerSender = workerSender;
        this.workerReceiver = workerReceiver;
        fileSystemObjectQueue = workerReceiver.objectQueue;
        worker = new WorkerThread();
        fileSystemScheduler = new FileSystemScheduler(hostPort, sessionTimeOut,
                    workerSender, workerReceiver, Constants.RANDOM.getValue());
        myIp = InetAddress.getLocalHost().getHostAddress();
        fileStorageInfo = new Hashtable<String, FileStorageLocalDataModel>();
    }

    public boolean initWorker() {
        try {
            startZooKeeper();
            register();
            workerThread = new Thread(worker);
            workerThread.start();
            return true;
        } catch (InterruptedException exception) {
            return false;
        } catch (IOException exception) {
            return false;
        }
    }

    public boolean shutDown() {
        try {
            stopZooKeeper();
            return true;
        } catch (InterruptedException exception) {
            return false;
        }
    }

    /**
     * This function gets the ip and name of the registered workers
     * @return a list of worker info
     * @throws InterruptedException
     * @throws KeeperException
     */
    public List<WorkerInstanceModel> getWorkers() throws InterruptedException, KeeperException {
        List<String> workerNameList = listNodes(Constants.WORKER_PATH.getValue());
        List<WorkerInstanceModel> workerList = new ArrayList<WorkerInstanceModel>();
        for (String workerName : workerNameList) {
            String workerPath = String.format("%s/%s", Constants.WORKER_PATH.getValue(), workerName);
            byte[] data = zooKeeper.getData(workerPath, false, null);
            String ipString = new String(data);
            workerList.add(new WorkerInstanceModel(ipString, workerName));
        }
        return workerList;
    }

    @Override
    public void process(WatchedEvent event) {
        // to be implemented
    }

    public void setStatus(String status) {
        this.status = status;
        updateStatus(status);
    }

    private class WorkerThread implements Runnable {
        public void run() {
            long sleepInterval = Long.parseLong(Constants.SLEEP_INTERVAL.getValue());
            CommunicationDataModel comData = null;
            while (true) {
                synchronized (comDataList) { // ???????????????/*/*/*/*/**/*/*/*/*/
                    if (comDataList.isEmpty()) {
                        comData = null;
                        try {
                            Thread.sleep(sleepInterval);
                        } catch (InterruptedException exception) {
                            // nothing to do
                        }
                    } else {
                        comData = comDataList.getFirst();
                        comDataList.removeFirst();
                    }
                }
                if (comData != null) {
                    if (comData.getAction().equals(CommunicationConstants.SEND.getValue())) {
                        // In this part, this worker is required to send file to an ip
                        FileDataModel fileDataModel = new FileDataModel(
                                comData.getActionDestinationIp(),
                                Integer.parseInt(Constants.FILE_RECEIVE_PORT.getValue()),
                                comData.getSourceFile()
                        );
                        workerSender.addFileTask(fileDataModel);
                    } else if (comData.getAction().equals(CommunicationConstants.DELETE.getValue())) {
                        // TODO: delete the file
                    } else if (comData.getAction().equals(CommunicationConstants.ADD_REPLICA.getValue())) {
                        // TODO: add replica
                    } else if (comData.getAction().equals(CommunicationConstants.ADD_REPLICAS.getValue())) {
                        // TODO: add two replicas
                    } else {
                        // TODO: I don't know...
                    }
                }
            }
        }
    }

    private class WorkerFileSystemThread implements Runnable {
        public void run() {
            long sleepInterval = Long.parseLong(Constants.SLEEP_INTERVAL.getValue());
            FileObjectModel fileSystemData = null;
            while (true) {
                synchronized (fileSystemObjectQueue) { // ???????????????/*/*/*/*/**/*/*/*/*/
                    if (fileSystemObjectQueue.isEmpty()) {
                        fileSystemData = null;
                        try {
                            Thread.sleep(sleepInterval);
                        } catch (InterruptedException exception) {
                            // nothing to do
                        }
                    } else {
                        fileSystemData = (FileObjectModel)fileSystemObjectQueue.getFirst();
                        fileSystemObjectQueue.removeFirst();
                    }
                }
                if (fileSystemData != null) {
                    if (fileSystemData.getActionType().equals(CommunicationConstants.PUT_PRIMARY_REPLICA.getValue())) {
                        ArrayList<String> replicaIps = fileSystemScheduler.scheduleFile(0L,
                                Integer.parseInt(Constants.REPLICATION_NUM.getValue()));
                        for (String replicaIp : replicaIps) {
                            FileDataModel addReplicaTask = new FileDataModel(
                                    replicaIp,
                                    Integer.parseInt(Constants.FILE_RECEIVE_PORT.getValue()),
                                    fileSystemData.getFilePath()
                            );
                            workerSender.addFileTask(addReplicaTask);
                        }
                        replicaIps.add(myIp);
                        FileStorageLocalDataModel fileStorageLocalDataModel = new FileStorageLocalDataModel(
                                fileSystemData.getFilePath(),
                                myIp,
                                0L,
                                true,
                                new HashSet<String>(replicaIps)
                        );

                        /**
                         * We have one write and maybe multiple reads
                         * do we need lock????
                         * */
                        fileStorageInfo.put(fileSystemData.getFilePath(), fileStorageLocalDataModel);
                        // TODO: get masterIp here
                        String masterIp = "";
                        CommunicationDataModel ackToMaster = new CommunicationDataModel(
                                myIp, masterIp, fileSystemData.getSenderIp(),
                                Constants.ADD_FILE_ACK.getValue(),
                                fileSystemData.getFilePath(),
                                fileSystemData.getFilePath(),
                                Integer.parseInt(Constants.CLIENT_COMMUNICATION_PORT.getValue())
                        );
                        synchronized (comDataList) {
                            comDataList.add(ackToMaster);
                        }
                    }
                }
            }
        }
    }

    /**
     * Maybe we should add a return to distinguish the success of register
     */
    private void register() {
        try {
            String myIp = InetAddress.getLocalHost().getHostAddress();
            zooKeeper.create(String.format("/worker/worker-%s", serverId),
                    myIp.getBytes(),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT,
                    createWorkerCallback,
                    null);
        } catch (UnknownHostException exception) {
            System.err.println("*************** Error : fail to get my address ****************");
        }
    }

    private AsyncCallback.StringCallback createWorkerCallback = new AsyncCallback.StringCallback() {
        public void processResult(int rc, String path, Object ctx, String name) {
            switch (KeeperException.Code.get(rc)) {
                case CONNECTIONLOSS:
                    // in this case, we retry the request
                    register();
                    break;
                case OK:
                    System.out.println("*************** Parent created ****************");
                    break;
                case NODEEXISTS:
                    System.out.println("*************** Parent already registered ****************");
                     break;
                default:
                    System.out.println(String.format("Error: %s %s",
                            KeeperException.create(KeeperException.Code.get(rc)), path));
            }
        }
    };

    private AsyncCallback.StatCallback statusUpdateCallback = new AsyncCallback.StatCallback() {
        public void processResult(int rc, String path, Object ctx, Stat stat) {
            switch(KeeperException.Code.get(rc)) {
                case CONNECTIONLOSS:
                    updateStatus((String)ctx);
                    return;
            }
        }
    };

    synchronized private void updateStatus(String status) {
        if (status.equals(this.status)) {
            zooKeeper.setData(String.format("/workers/%s", name), status.getBytes(), -1,
                                            statusUpdateCallback, status);
        }
    }
}
