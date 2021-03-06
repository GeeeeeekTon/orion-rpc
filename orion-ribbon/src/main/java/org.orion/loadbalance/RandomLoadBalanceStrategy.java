package org.orion.loadbalance;

import com.alibaba.fastjson.JSONObject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.orion.common.LoadConfig;
import org.orion.common.ZKconfig;
import org.orion.curator.CuratorManager;
import org.springframework.beans.factory.InitializingBean;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @ClassName RandomLoadBalanceStrategy
 * 权重随机算法
 * @Author Leo
 * @Description //TODO
 * @Date: 2019/5/30 19:38
 **/
@Slf4j
public class RandomLoadBalanceStrategy implements ILoadBalanceStrategy, InitializingBean {
    /**
     * key:父路径
     * <p>
     * value：Map-->key:子路径，value:子路径中的值
     */
    @Getter
    private static Map<String, ConcurrentHashMap<String, String>> nodeCacheMap = new ConcurrentHashMap();

    @Override
    public void afterPropertiesSet() throws Exception {
        nodeCacheWatcher();
    }

    public void nodeCacheWatcher() {
        this.initData();
        List<String> strings = CuratorManager.getChildren(ZKconfig.ZK_REGISTER_PATH);
        if (!CollectionUtils.isEmpty(strings)) {
            strings.forEach(i -> {
                PathChildrenCache pathChildrenCache = CuratorManager.registerPathChildListener(ZKconfig.ZK_REGISTER_PATH + "/" + i, (client, event) -> {
                    ChildData childData = event.getData();
                    if (childData == null) {
                        return;
                    }
                    String path = childData.getPath();
                    if (StringUtils.isEmpty(path)) {
                        return;
                    }
                    String[] paths = path.split("/");
                    if (paths == null || path.length() < 4) {
                        return;
                    }
                    String ip = paths[3];
                    ConcurrentHashMap<String, String> map = nodeCacheMap.get(i);
                    switch (event.getType()) {
                        case CHILD_ADDED:
                            log.info("正在新增子节点：" + childData.getPath());
                            map.put(ip, new String(childData.getData()));
                            break;
                        case CHILD_UPDATED:
                            log.info("正在更新子节点：" + childData.getPath());
                            map.put(ip, new String(childData.getData()));
                            break;
                        case CHILD_REMOVED:
                            log.info("子节点被删除");
                            map.remove(ip);
                            break;
                        case CONNECTION_LOST:
                            log.info("连接丢失");
                            clearData();
                            break;
                        case CONNECTION_SUSPENDED:
                            log.info("连接被挂起");
                            break;
                        case CONNECTION_RECONNECTED:
                            log.info("恢复连接");
                            initData();
                            break;
                    }
                });
            });
        }
    }

    public ConcurrentHashMap<String, String> discovery(String serverName) {
        try {
            ConcurrentHashMap<String, String> cacheMap = nodeCacheMap.get(serverName);
            if (MapUtils.isNotEmpty(cacheMap)) {
                return cacheMap;
            }
            return fetchNodeFromRegister(serverName);
        } catch (Exception e) {
            log.error("worker discover failed ", e);
        }
        return new ConcurrentHashMap<>();
    }

    public String idleWorker(String serverName) {
        try {
            Map<String, String> map = discovery(serverName);
            Iterator<Map.Entry<String, String>> iter = map.entrySet().iterator();
            Integer limit = LoadConfig.WEIGHT_LIMIT;
            String temp = null;
            while (iter.hasNext()) {
                Map.Entry<String, String> entry = iter.next();
                int weight = NumberUtils.createInteger(entry.getValue());
                if (weight <= limit) {
                    limit = weight;
                    temp = entry.getKey();
                }
            }
            if (temp != null) {
                return temp;
            }
        } catch (Exception e) {
            log.error("fetch idleWorker failed", e);
        }
        return null;
    }

    private ConcurrentHashMap<String, String> fetchNodeFromRegister(String serverName) {
        try {
            String rootPath = ZKconfig.ZK_REGISTER_PATH + "/" + serverName;
            List<String> strings = CuratorManager.getChildren(rootPath);
            if (!CollectionUtils.isEmpty(strings)) {
                ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>(strings.size());
                strings.forEach(i -> {
                    try {
                        map.put(i, CuratorManager.getData(rootPath + "/" + i));
                    } catch (Exception e) {
                        log.error(e.getMessage(), e);
                    }
                });
                return map;
            }
        } catch (Exception e) {
            log.error("worker discover failed ", e);
        }
        return new ConcurrentHashMap<>();
    }

    public String getNodeCache() {
        return JSONObject.toJSONString(nodeCacheMap);
    }

    public void initData() {
        if (MapUtils.isNotEmpty(nodeCacheMap)) {
            nodeCacheMap.clear();
        }
        List<String> strings = CuratorManager.getChildren(ZKconfig.ZK_REGISTER_PATH);
        if (!CollectionUtils.isEmpty(strings)) {
            strings.forEach(i -> {
                try {
                    ConcurrentHashMap<String, String> map = fetchNodeFromRegister(i);
                    if (MapUtils.isNotEmpty(map)) {
                        nodeCacheMap.put(i, map);
                    }
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            });
        }
    }

    public void clearData() {
        if (MapUtils.isNotEmpty(nodeCacheMap)) {
            nodeCacheMap.clear();
        }
    }


    @Override
    public String loadBalance(String serverName) {
        return idleWorker(serverName);
    }
}
