package com.dslztx.zookeeper;

import org.apache.zookeeper.AsyncCallback;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MQNodesSync {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /**
     * 与MQ节点通信的协议前缀
     */
    private static final String PROTOTYPE = "tcp://";

    /**
     * ZooKeeper中维护MQ地址和端口号数据结构的顶层路径
     */
    private static final String DIR = "/mqs";

    /**
     * Spring中配置的ZooKeeperSession对象的名称
     */
    private static final String BEANNAME = "zooKeeperSession";

    /**
     * 管理ZooKeeper Session的对象
     */
    private ZooKeeperSession session;

    /**
     * MQ节点组名
     */
    private String mqNodeGroup;

    /**
     * MQ客户端实例管理器类实例
     */
    private MQClientManager manager;

    /**
     * 异步调用操作是否完成
     */
    private volatile boolean firstRequestFinish = false;

    public MQNodesSync(String mqNodeGroup) {
        try {
            BeanFactory beanFactory = new ClassPathXmlApplicationContext("applicationContext.xml");

            // 从Spring中获取ZooKeeperSession对象
            this.session = (ZooKeeperSession) beanFactory.getBean(BEANNAME);

            this.mqNodeGroup = mqNodeGroup;
        } catch (Exception e) {
            logger.error("", e);
        }
    }

    /**
     * 设置回调对象
     *
     * @param manager
     */
    public void setMQClientManager(MQClientManager manager) {
        this.manager = manager;
    }

    /**
     * 开始ZooKeeper Session
     * 
     * @throws IOException
     */
    public void startSession() throws IOException {
        session.startSession();
    }

    /**
     * 开始ZooKeeper Session，并获取最新的MQ节点地址和端口号列表
     * 
     * @throws IOException
     */
    public void start() throws IOException {
        // 开始ZooKeeper Session
        startSession();

        // 与ZooKeeper的Session建立后，才允许调用ZooKeeper的API接口
        while (!session.isConnected()) {
            try {
                Thread.sleep(100);
            } catch (Exception e) {
                logger.error("", e);
            }
        }

        // 获取最新的MQ节点地址和端口号列表
        getMQNodes();

        // 等待第一次异步调用操作完成
        while (!firstRequestFinish) {
            try {
                Thread.sleep(100);
            } catch (Exception e) {
                logger.error("", e);
            }
        }
    }

    /**
     * 异步获取最新的MQ节点地址和端口号列表
     */
    void getMQNodes() {
        // 在异步获取时，同时设置一个监听器，当监听到该路径下的子节点数量变化时，会自动回调该监听器下的处理方法
        session.getZk().getChildren(DIR + "/" + this.mqNodeGroup, mqNodesChangeWatcher, getMQNodesCallback, null);
    }

    Watcher mqNodesChangeWatcher = new Watcher() {
        /**
         * 监听到路径下的子节点数量变化时，自动回调的处理方法
         * 
         * @param e
         */
        public void process(WatchedEvent e) {
            if (e.getType() == Event.EventType.NodeChildrenChanged) {
                // 获取最新的MQ节点地址和端口号列表
                getMQNodes();
            }
        }
    };

    AsyncCallback.ChildrenCallback getMQNodesCallback = new AsyncCallback.ChildrenCallback() {

        /**
         * 异步获取最新的MQ节点地址和端口号列表的回调方法
         */
        public void processResult(int rc, String path, Object ctx, List<String> children) {
            switch (KeeperException.Code.get(rc)) {
                case CONNECTIONLOSS:
                    logger.error("Connection Loss in getting children");
                    getMQNodes();

                    firstRequestFinish = true;

                    break;
                case OK:
                    logger.info("Succesfully got a list of mqnodes: " + children.size() + " mqnodes");

                    // 给MQ节点地址和端口号增加协议前缀
                    children = addPrototype(children);

                    // 回调
                    manager.syncMQNodes(children);

                    firstRequestFinish = true;

                    break;
                default:
                    firstRequestFinish = true;
                    logger.error("getMQNodes failed", KeeperException.create(KeeperException.Code.get(rc), path));
            }
        }

        /**
         * 给MQ节点地址和端口号增加协议前缀
         * 
         * @param children
         * @return
         */
        private List<String> addPrototype(List<String> children) {
            List<String> result = new ArrayList<String>();
            for (String mqNode : children) {
                result.add(PROTOTYPE + mqNode);
            }
            return result;
        }
    };
}
