package cluster.instance;

import network.datamodel.CommunicationDataModel;
import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.AsyncCallback.StringCallback;
import usertool.Constants;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.LinkedList;
import java.util.Random;

import static org.apache.zookeeper.ZooDefs.Ids.OPEN_ACL_UNSAFE;

public class Master extends BasicWatcher{

    private boolean isLeader;
    private String serverId;
    private LinkedList<CommunicationDataModel> comDataList;

    public Master(String hostPort, int sessionTimeOut) {
        super(hostPort, sessionTimeOut);
        Random random = new Random();
        serverId = String.valueOf(random.nextInt());
        comDataList = new LinkedList<CommunicationDataModel>();
    }

    public void runForMaster() {
        masterRegistration();
        if (isLeader) {
            // I am Master !!
            bootstrap();
        }
    }

    public boolean shutDown() {
        try {
            stopZooKeeper();
            // add sth to stop the thread
            return true;
        } catch (InterruptedException exception) {
            return false;
        }
    }

    /* a queue should be in Master class for received user request*/
    private class MasterThread implements Runnable {
        // this thread should deal with client request from a queue

        public void run() {
            long sleepInterval = Long.parseLong(Constants.SLEEP_INTERVAL.getValue());
            String selfIp = "";
            try {
                selfIp = InetAddress.getLocalHost().getHostAddress();
            } catch (UnknownHostException exception) {
                // not sure what to do here
            }
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
                    String action = comData.getAction();
                    if (action.equals(Constants.ADD_FILE.getValue())) {
                        // TODO: use some algorithm to find primary replica
                        String mainReplicaIp = "";

                        CommunicationDataModel ack = new CommunicationDataModel(); // sth should be here
                        //TODO: add ack to sender

                    } else if (action.equals(Constants.REQUEST_FILE.getValue())) {

                    } else if (action.equals(Constants.REMOVE_FILE.getValue())) {

                    } else {
                        System.out.println("*************** Incorrect input *****************");
                    }
                }
            }
        }
    }

    private void bootstrap() {
        // this function will create a bunch of folders in zookeeper for coordination
        createParent("/workers", new byte[0]);
        createParent("/assign", new byte[0]);
        createParent("/tasks", new byte[0]);
        createParent("/status", new byte[0]);
    }

    /**
     * This function deal with the master related events. Now it should include master failure
     * @param event : event received from zooKeeper servers
     */
    @Override
    public void process(WatchedEvent event) {
        // to be implemented
        if (event.getType() == Event.EventType.NodeDeleted) {
            runForMaster();
        }
    }

    public boolean existMaster() {
        while (true) {
            try {
                Stat stat = new Stat();
                byte data[] = zooKeeper.getData("/master", false, stat);
                isLeader = new String(data).equals(serverId);
                return true;
            } catch(InterruptedException exception) {
                return false;
            } catch(KeeperException exception) {
                // do nothing
            }
        }
    }

    private void masterRegistration() {
        while (true) {
            try {
                zooKeeper.create("/master", serverId.getBytes(), OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
                isLeader = true;
                break;
            } catch (KeeperException.NodeExistsException exception) {
                isLeader = false;
                break;
            }  catch (InterruptedException exception) {
                isLeader = false;
                break;
            } catch (KeeperException.ConnectionLossException exception) {
                // do nothing, we should try again
            } catch (KeeperException exception) {
                isLeader = false;
                break;
            }
            if (existMaster()) {
                isLeader = false;
                break;
            }
        }
    }

    private void createParent(String path, byte[] data) {
        zooKeeper.create(path, data,
                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT,
                createParentCallback,
                data);
    }

    private AsyncCallback.StringCallback createParentCallback = new StringCallback() {
        public void processResult(int rc, String path, Object ctx, String name) {
            switch (KeeperException.Code.get(rc)) {
                case CONNECTIONLOSS:
                    // in this case, we retry the request
                    createParent(path, (byte[]) ctx);
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
}
