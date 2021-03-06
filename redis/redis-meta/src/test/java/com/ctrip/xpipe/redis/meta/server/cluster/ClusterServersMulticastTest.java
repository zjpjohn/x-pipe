package com.ctrip.xpipe.redis.meta.server.cluster;

import org.junit.Test;
import org.springframework.web.client.RestTemplate;

import com.ctrip.xpipe.redis.core.entity.ClusterMeta;
import com.ctrip.xpipe.redis.core.metaserver.MetaServerConsoleService;
import com.ctrip.xpipe.spring.RestTemplateFactory;

/**
 * @author wenchao.meng
 *
 * Sep 1, 2016
 */
public class ClusterServersMulticastTest extends AbstractMetaServerClusterTest{
	
	private int metaServerCount = 3;
	private RestTemplate restTemplate = RestTemplateFactory.createCommonsHttpRestTemplate();
	
	@Test
	public void simpleTest(){
	}
	
	@Test
	public void testClusterChanged() throws Exception{
		
		createMetaServers(metaServerCount);
		sleep(1000);
		logger.info(remarkableMessage("[testClusterChanged][begin send cluster change message]"));
		ClusterMeta clusterMeta = randomCluster();
		
		for(TestMetaServer server : getServers()){
			String path = getChangeClusterPath(server);
			logger.info("[testClusterChanged]{}", path);
			restTemplate.postForEntity(path, clusterMeta, String.class, clusterMeta.getId());
			restTemplate.put(path, clusterMeta, String.class, clusterMeta.getId());
			restTemplate.delete(path, clusterMeta.getId());
		}
	}

	private ClusterMeta randomCluster() {
		
		ClusterMeta clusterMeta = new ClusterMeta();
		clusterMeta.setId(getTestName());
		return clusterMeta;
	}

	private String getChangeClusterPath(TestMetaServer server) {
		return String.format("http://localhost:%d/%s/%s", server.getServerPort(), MetaServerConsoleService.PATH_PREFIX, MetaServerConsoleService.PATH_CLUSTER_CHANGE);
	}

}
