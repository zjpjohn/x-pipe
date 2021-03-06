package com.ctrip.xpipe.redis.meta.server.cluster.impl;


import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.CuratorWatcher;
import org.apache.zookeeper.WatchedEvent;
import org.springframework.beans.factory.annotation.Autowired;

import com.ctrip.xpipe.api.codec.Codec;
import com.ctrip.xpipe.api.lifecycle.TopElement;
import com.ctrip.xpipe.observer.AbstractLifecycleObservable;
import com.ctrip.xpipe.observer.NodeAdded;
import com.ctrip.xpipe.observer.NodeDeleted;
import com.ctrip.xpipe.observer.NodeModified;
import com.ctrip.xpipe.redis.core.meta.MetaZkConfig;
import com.ctrip.xpipe.redis.meta.server.cluster.ClusterServer;
import com.ctrip.xpipe.redis.meta.server.cluster.ClusterServerInfo;
import com.ctrip.xpipe.redis.meta.server.cluster.ClusterServers;
import com.ctrip.xpipe.redis.meta.server.cluster.RemoteClusterServerFactory;
import com.ctrip.xpipe.redis.meta.server.config.MetaServerConfig;
import com.ctrip.xpipe.zk.ZkClient;

/**
 * @author wenchao.meng
 *
 * Jul 22, 2016
 */
public class AbstractClusterServers<T extends ClusterServer> extends AbstractLifecycleObservable implements ClusterServers<T>, TopElement, CuratorWatcher{
	
	private Map<Integer, T> servers = new ConcurrentHashMap<>();
	
	@Autowired
	private MetaServerConfig  metaServerConfig;
	
	@Autowired
	private ZkClient zkClient;
		
	@Autowired
	private T currentServer;
	
	@Autowired
	private RemoteClusterServerFactory<T> remoteClusterServerFactory;
	
	private ScheduledExecutorService scheduled = Executors.newScheduledThreadPool(1);
	private ScheduledFuture<?> future;
		
	@Override
	protected void doInitialize() throws Exception {
	
	}
	
	@Override
	protected void doStart() throws Exception {
		
		CuratorFramework client = zkClient.get();

		client.createContainers(MetaZkConfig.getMetaServerRegisterPath());
		
		watchServers();
		
		future = scheduled.scheduleWithFixedDelay(new Runnable() {
			
			@Override
			public void run() {
				try{
					childrenChanged();
				}catch(Throwable th){
					logger.error("[doStart]", th);
				}
				
			}
		}, 0,  metaServerConfig.getClusterServersRefreshMilli(), TimeUnit.MILLISECONDS);
		
	}

	private void watchServers() throws Exception {
		
		zkClient.get().getChildren().usingWatcher(this).forPath(MetaZkConfig.getMetaServerRegisterPath());		
	}

	@Override
	public T currentClusterServer() {
		
		return currentServer;
	}

	@Override
	public T getClusterServer(int serverId) {
		return servers.get(serverId);
	}

	@Override
	public int getOrder() {
		return 0;
	}

	@Override
	public void process(WatchedEvent event) throws Exception {
		logger.info("[process]{}", event);
		watchServers();
		childrenChanged();
	}

	private synchronized void childrenChanged() throws Exception {

		logger.info("[childrenChanged][start][{}]{}", currentServerId(), servers);
		
		CuratorFramework client = zkClient.get();
		List<String> children = client.getChildren().forPath(MetaZkConfig.getMetaServerRegisterPath());
		
		Set<Integer> currentServers = new HashSet<>();
		for(String child : children){
			
			int serverId = Integer.parseInt(child);
			byte []data = client.getData().forPath(MetaZkConfig.getMetaServerRegisterPath() + "/" + child);
			ClusterServerInfo  info = Codec.DEFAULT.decode(data, ClusterServerInfo.class);
			
			logger.debug("[childrenChanged][{}]{},{}", currentServerId(), serverId, info);
			currentServers.add(serverId);

			ClusterServer server = servers.get(serverId);
			if(server == null){
				logger.info("[childrenChanged][{}][createNew]{}{}", currentServerId(), child, info);
				T remoteServer = remoteClusterServerFactory.createClusterServer(serverId, info);
				servers.put(serverId, remoteServer);
				logger.info("[childrenChanged][{}][createNew]{}", currentServerId(), servers);
				serverAdded(remoteServer);
			}else{
				if(!info.equals(server.getClusterInfo())){
					
					logger.info("[childrenChanged][{}][clusterInfoChanged]{}{}", currentServerId(), child, info, server.getClusterInfo());
					T newServer = remoteClusterServerFactory.createClusterServer(serverId, info);
					servers.put(serverId, newServer);
					serverChanged(server, newServer);
					
				}
			}
		}

		
		for(Integer old : servers.keySet()){
			if(!currentServers.contains(old)){
				
				ClusterServer serverInfo = servers.remove(old);
				logger.info("[childrenChanged][remote not exist][{}]{}, {}, current:{}", currentServerId(), old, serverInfo);
				remoteDelted(serverInfo);
				
			}
		}

		logger.info("[childrenChanged][ end ][{}]{}", currentServerId(), servers);
	}

	
	private Object currentServerId() {
		return currentServer.getServerId();
	}

	private void remoteDelted(ClusterServer serverInfo) {
		notifyObservers(new NodeDeleted<ClusterServer>(serverInfo));
	}

	private void serverChanged(ClusterServer oldServer, ClusterServer newServer) {
		notifyObservers(new NodeModified<ClusterServer>(oldServer, newServer));
	}

	private void serverAdded(ClusterServer remoteServer) {
		notifyObservers(new NodeAdded<ClusterServer>(remoteServer));
	}

	public void setMetaServerConfig(MetaServerConfig metaServerConfig) {
		this.metaServerConfig = metaServerConfig;
	}
	
	public void setZkClient(ZkClient zkClient) {
		this.zkClient = zkClient;
	}
	
	public void setCurrentServer(T currentServer) {
		this.currentServer = currentServer;
	}
	
	public void setRemoteClusterServerFactory(RemoteClusterServerFactory<T> remoteClusterServerFactory) {
		this.remoteClusterServerFactory = remoteClusterServerFactory;
	}

	@Override
	public Set<T> allClusterServers() {
		return new HashSet<>(servers.values());
	}

	
	@Override
	protected void doStop() throws Exception {
		
		if(future != null){
			future.cancel(true);
			future = null;
		}
	}
	
	@Override
	public void refresh() throws Exception {
		childrenChanged();
	}

	@Override
	public boolean exist(int serverId) {
		return servers.get(serverId) != null;
	}

	@Override
	public Map<Integer, ClusterServerInfo> allClusterServerInfos() {
		
		Map<Integer, ClusterServerInfo> result = new HashMap<>();
		for(Entry<Integer, T> entry : servers.entrySet()){
			result.put(entry.getKey(), entry.getValue().getClusterInfo());
		}
		return result;
	}
	
	
}
