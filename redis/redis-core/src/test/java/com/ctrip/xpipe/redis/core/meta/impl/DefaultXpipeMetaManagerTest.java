package com.ctrip.xpipe.redis.core.meta.impl;



import java.util.List;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.unidal.tuple.Pair;

import com.ctrip.xpipe.redis.core.AbstractRedisTest;
import com.ctrip.xpipe.redis.core.entity.KeeperMeta;
import com.ctrip.xpipe.redis.core.entity.RedisMeta;
import com.ctrip.xpipe.redis.core.meta.MetaException;
import com.ctrip.xpipe.redis.core.meta.impl.DefaultXpipeMetaManager;



/**
 * @author wenchao.meng
 *
 * Jun 23, 2016
 */
public class DefaultXpipeMetaManagerTest extends AbstractRedisTest{
	
	private DefaultXpipeMetaManager metaManager;
	
	private String dc = "jq", clusterId = "cluster1", shardId = "shard1";

	@Before
	public void beforeDefaultFileDaoTest() throws Exception{
		
		metaManager = (DefaultXpipeMetaManager) DefaultXpipeMetaManager.buildFromFile("file-dao-test.xml");
		add(metaManager);
	}
	
	@Test
	public void testUpdateKeeperActive() throws MetaException{
		
		
		List<KeeperMeta> backups = metaManager.getKeeperBackup(dc, clusterId, shardId);
		
		Assert.assertNotNull(metaManager.getKeeperActive(dc, clusterId, shardId));;
		
		KeeperMeta backup = backups.get(0);
		
		metaManager.updateKeeperActive(dc, clusterId, shardId, backups.get(0));
		
		KeeperMeta newActive = metaManager.getKeeperActive(dc, clusterId, shardId);
		Assert.assertEquals(backup.getIp(), newActive.getIp());
		Assert.assertEquals(backup.getPort(), newActive.getPort());
		
		
		metaManager.updateKeeperActive(dc, clusterId, shardId, new KeeperMeta());
		Assert.assertNull(metaManager.getKeeperActive(dc, clusterId, shardId));
	}

	@Test
	public void testUpdateRedisMaster() throws MetaException{
		
		Pair<String, RedisMeta> redisMaster = metaManager.getRedisMaster(clusterId, shardId);
		Assert.assertEquals(redisMaster.getKey(), "jq");
		boolean result = metaManager.updateRedisMaster(redisMaster.getKey(), clusterId, shardId, redisMaster.getValue());
		Assert.assertTrue(!result);

		KeeperMeta activeKeeper = null;
		for(KeeperMeta keeperMeta : metaManager.getKeepers(dc, clusterId, shardId)){
			if(keeperMeta.getMaster().equals(String.format("%s:%d", redisMaster.getValue().getIp(), redisMaster.getValue().getPort()))){
				activeKeeper = keeperMeta;
			}
		}
		Assert.assertNotNull(activeKeeper);

		for(RedisMeta redis : metaManager.getRedises(dc, clusterId, shardId)){
			
			if(!redis.equals(redisMaster.getValue())){
				String master = String.format("%s:%d", redis.getIp(), redis.getPort());
				Assert.assertNotEquals(activeKeeper.getMaster(), master);
				result = metaManager.updateRedisMaster(redisMaster.getKey(), clusterId, shardId, redis);
				Assert.assertTrue(result);
				
				KeeperMeta active = metaManager.getKeeperActive(redisMaster.getKey(), clusterId, shardId);
				Assert.assertEquals(active.getMaster(), master);
			}
		}
	}

	@Test
	public void testUpdateUpstream() throws MetaException{
		
		String activeDc = metaManager.getActiveDc(clusterId);
		try{
			metaManager.updateUpstreamKeeper(activeDc, clusterId, shardId, "");
			Assert.fail();
		}catch(Exception e){
		}

		List<String> backDcs = metaManager.getBackupDc(clusterId);
		
		Assert.assertTrue(backDcs.size() >= 1);
		
		
		for(String dc : backDcs){
			
			String upstream = metaManager.getUpstream(dc, clusterId, shardId);
			Assert.assertNull(upstream);
			
			String address = null;
			Assert.assertFalse(metaManager.updateUpstreamKeeper(dc, clusterId, shardId, address));
			
			address = "127.0.0.1:8080";
			Assert.assertTrue(metaManager.updateUpstreamKeeper(dc, clusterId, shardId, address));
			Assert.assertFalse(metaManager.updateUpstreamKeeper(dc, clusterId, shardId, address));
		}		
		
	}

}
