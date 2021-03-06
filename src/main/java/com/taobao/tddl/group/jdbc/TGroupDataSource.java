package com.taobao.tddl.group.jdbc;

import java.io.PrintWriter;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.sql.DataSource;

import com.taobao.tddl.common.TddlConstants;
import com.taobao.tddl.common.model.DBType;
import com.taobao.tddl.common.model.DataSourceType;
import com.taobao.tddl.common.utils.mbean.TddlMBeanServer;
import com.taobao.tddl.group.config.GroupConfigManager;
import com.taobao.tddl.group.dbselector.DBSelector;
import com.taobao.tddl.group.exception.TGroupDataSourceException;
import com.taobao.tddl.group.listener.DataSourceChangeListener;
import com.taobao.tddl.monitor.Monitor;

/**
 * TGroupDataSource并不是像名字所暗示的那样有一组DataSource，
 * 而是指TGroupDataSource内部包含一组(>=1个)同构的数据库， 这一组数据库的不同个体有不同的读写优先级和权重，
 * 当读写数据时也只是按读写优先级和权重对其中的一个数据库操作， 如果第一个数据库读写失败了，再尝试下一个数据库，
 * 如果第一个数据库读写成功了，直接返回结果给应用层， 其他数据库的同步更新由底层数据库内部完成， TGroupDataSource不负责数据同步。
 * 使用TGroupDataSource的步骤:
 * 
 * <pre>
 * TGroupDataSource tGroupDataSource = new TGroupDataSource();
 * tGroupDataSource.setDbGroupKey(&quot;myDbGroup&quot;);
 * // ......调用其他setter
 * tGroupDataSource.init();
 * tGroupDataSource.getConnection();
 * </pre>
 * 
 * @author yangzhu
 * @author linxuan
 */
public class TGroupDataSource implements DataSource {

    public static final String                    VERSION                   = "2.4.1";
    public static final String                    PREFIX                    = "group/";
    public static final String                    EXTRA_PREFIX              = "com.taobao.tddl.jdbc.extra_config.group_V"
                                                                              + VERSION + "_";
    private GroupConfigManager                    configManager;

    /**
     * 下面三个为一组，支持本地配置
     */
    private String                                dsKeyAndWeightCommaArray;
    private DataSourceFetcher                     dataSourceFetcher;
    private DBType                                dbType                    = DBType.MYSQL;
    private String                                appName;                                                           // app名字
    private String                                unitName;                                                          // 单元化名字
    private String                                dbGroupKey;
    private String                                fullDbGroupKey            = null;                                  // dataId
    private int                                   retryingTimes             = 3;                                     // 默认读写失败时重试3次
    private long                                  configReceiveTimeout      = TddlConstants.DIAMOND_GET_DATA_TIMEOUT; // 取配置信息的默认超时时间为30秒
    // 当运行期间主备发生切换时是否需要查找第一个可写的库
    private boolean                               autoSelectWriteDataSource = false;
    /*
     * ========================================================================
     * 以下是保留当前写操作是在哪个库上执行的, 满足类似日志库插入的场景
     * ======================================================================
     */
    private static ThreadLocal<DataSourceWrapper> targetThreadLocal;

    // 下面两个字段当建立实际的DataSource时必须传递过去
    // jdbc规范: DataSource刚建立时LogWriter为null
    private PrintWriter                           out                       = null;
    // jdbc规范: DataSource刚建立时LoginTimeout为0
    private int                                   seconds                   = 0;

    /**
     * 使用tbdatasource还是druid
     */
    private DataSourceType                        dataSourceType            = DataSourceType.DruidDataSource;

    public TGroupDataSource(){
    }

    public TGroupDataSource(String dbGroupKey, String appName){
        this.dbGroupKey = dbGroupKey;
        this.appName = appName;
    }

    public TGroupDataSource(String dbGroupKey, String appName, DataSourceType dataSourceType){
        this.dbGroupKey = dbGroupKey;
        this.appName = appName;
        this.dataSourceType = dataSourceType;
    }

    /**
     * 基于dbGroupKey、appName来初始化多个TAtomDataSource
     * 
     * @throws com.taobao.tddl.jdbc.group.exception.ConfigException
     */
    public void init() {
        if (dsKeyAndWeightCommaArray != null) {
            // 本地配置方式：dsKeyAndWeightCommaArray + dataSourceFetcher + dyType
            DataSourceFetcher wrapper = new DataSourceFetcher() {

                @Override
                public DataSource getDataSource(String key) {
                    return dataSourceFetcher.getDataSource(key);
                }

                @Override
                public DBType getDataSourceDBType(String key) {
                    DBType type = dataSourceFetcher.getDataSourceDBType(key);
                    return type == null ? dbType : type; // 如果dataSourceFetcher没dbType，用tgds的dbType
                }
            };
            List<DataSourceWrapper> dss = GroupConfigManager.buildDataSourceWrapper(dsKeyAndWeightCommaArray, wrapper);
            init(dss);
        } else {
            checkProperties();
            configManager = new GroupConfigManager(this);
            configManager.init();
        }

        Monitor.setAppName(appName);
    }

    public void init(DataSourceWrapper... dataSourceWrappers) {
        init(Arrays.asList(dataSourceWrappers));
    }

    public void init(List<DataSourceWrapper> dataSourceWrappers) {
        configManager = new GroupConfigManager(this);
        configManager.init(dataSourceWrappers);
    }

    public static TGroupDataSource build(String groupKey, String dsWeights, DataSourceFetcher fetcher,
                                         DataSourceType dataSourceType) {
        List<DataSourceWrapper> dss = GroupConfigManager.buildDataSourceWrapper(dsWeights, fetcher);
        TGroupDataSource tGroupDataSource = new TGroupDataSource();
        tGroupDataSource.setDataSourceType(dataSourceType);
        tGroupDataSource.setDbGroupKey(groupKey);
        tGroupDataSource.init(dss);
        return tGroupDataSource;
    }

    /**
     * 如果构造的是TAtomDataSource，必须检查dbGroupKey、appName两个属性的值是否合法
     */
    private void checkProperties() {
        if (dbGroupKey == null) {
            throw new TGroupDataSourceException("dbGroupKey不能为null");
        }
        dbGroupKey = dbGroupKey.trim();
        if (dbGroupKey.length() < 1) {
            throw new TGroupDataSourceException("dbGroupKey的长度要大于0，前导空白和尾部空白不算在内");
        }

        if (appName == null) {
            throw new TGroupDataSourceException("appName不能为null");
        }
        appName = appName.trim();
        if (appName.length() < 1) {
            throw new TGroupDataSourceException("appName的长度要大于0，前导空白和尾部空白不算在内");
        }

        if (dataSourceType == null) {
            throw new TGroupDataSourceException("dataSouceType不能为null");
        }
    }

    /**
     * 危险接口。一般用于测试。应用也可以直接通过该接口重置数据源配置
     */
    public void resetDbGroup(String configInfo) {
        configManager.resetDbGroup(configInfo);
    }

    // 包访问级别，调用者不能缓存，否则会失去动态性
    DBSelector getDBSelector(boolean isRead) {
        return configManager.getDBSelector(isRead, this.autoSelectWriteDataSource);
    }

    /**
     * 通过spring注入或直接调用该方法开启、关闭目标库记录
     */
    public void setTracerWriteTarget(boolean isTraceTarget) {
        if (isTraceTarget) {
            if (targetThreadLocal == null) {
                targetThreadLocal = new ThreadLocal<DataSourceWrapper>();
            }
        } else {
            targetThreadLocal = null;
        }
    }

    /**
     * 在执行完写操作后，调用改方法获得当前线程写操作是在哪个数据源执行的 获取完自动立即清空
     */
    public DataSourceWrapper getCurrentTarget() {
        if (targetThreadLocal == null) {
            return null;
        }
        DataSourceWrapper dsw = targetThreadLocal.get();
        targetThreadLocal.remove();
        return dsw;
    }

    /**
     * 下游调用该方法设置目标库
     */
    void setWriteTarget(DataSourceWrapper dsw) {
        if (targetThreadLocal != null) {
            targetThreadLocal.set(dsw);
        }
    }

    /*
     * ========================================================================
     * 遍历需求API
     * ======================================================================
     */
    // 在ConfigManager中我们将配置信息最终封装为读写DBSelector，要得到从dbKey到DataSource的映射，将DBSelector中的信息方向输出。
    public Map<String, DataSource> getDataSourceMap() {
        Map<String, DataSource> dsMap = new LinkedHashMap<String, DataSource>();
        dsMap.putAll(this.getDBSelector(true).getDataSources());
        dsMap.putAll(this.getDBSelector(false).getDataSources());
        return dsMap;
    }

    public Map<String, DataSource> getDataSourcesMap(boolean isRead) {
        return this.getDBSelector(isRead).getDataSources();
    }

    public void setDataSourceChangeListener(DataSourceChangeListener dataSourceChangeListener) {
        this.configManager.setDataSourceChangeListener(dataSourceChangeListener);
    }

    /*
     * ========================================================================
     * 以下是javax.sql.DataSource的API实现
     * ======================================================================
     */

    @Override
    public TGroupConnection getConnection() throws SQLException {
        return new TGroupConnection(this);
    }

    @Override
    public TGroupConnection getConnection(String username, String password) throws SQLException {
        return new TGroupConnection(this, username, password);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return out;
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        this.out = out;
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return seconds;
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        this.seconds = seconds;
    }

    public static void setShutDownMBean(boolean shutDownMBean) {
        TddlMBeanServer.shutDownMBean = shutDownMBean;
    }

    public String getUnitName() {
        return unitName;
    }

    public void setUnitName(String unitName) {
        this.unitName = unitName;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getDbGroupKey() {
        return dbGroupKey;
    }

    public String getFullDbGroupKey() {
        if (fullDbGroupKey == null) {
            fullDbGroupKey = PREFIX + getDbGroupKey();
        }
        return fullDbGroupKey;
    }

    public String getDbGroupExtraConfigKey() {
        return EXTRA_PREFIX + getDbGroupKey() + "." + getAppName();
    }

    public void setDbGroupKey(String dbGroupKey) {
        this.dbGroupKey = dbGroupKey;
    }

    public int getRetryingTimes() {
        return retryingTimes;
    }

    public void setRetryingTimes(int retryingTimes) {
        this.retryingTimes = retryingTimes;
    }

    public long getConfigReceiveTimeout() {
        return configReceiveTimeout;
    }

    public void setConfigReceiveTimeout(long configReceiveTimeout) {
        this.configReceiveTimeout = configReceiveTimeout;
    }

    public void setDsKeyAndWeightCommaArray(String dsKeyAndWeightCommaArray) {
        this.dsKeyAndWeightCommaArray = dsKeyAndWeightCommaArray;
    }

    public boolean getAutoSelectWriteDataSource() {
        return autoSelectWriteDataSource;
    }

    public void setAutoSelectWriteDataSource(boolean autoSelectWriteDataSource) {
        this.autoSelectWriteDataSource = autoSelectWriteDataSource;
    }

    public void setDataSourceFetcher(DataSourceFetcher dataSourceFetcher) {
        this.dataSourceFetcher = dataSourceFetcher;
    }

    public void setDbType(DBType dbType) {
        this.dbType = dbType;
    }

    public String getDsKeyAndWeightCommaArray() {
        return dsKeyAndWeightCommaArray;
    }

    public static final String getFullDbGroupKey(String dbGroupKey) {
        return PREFIX + dbGroupKey;
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return this.getClass().isAssignableFrom(iface);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        try {
            return (T) this;
        } catch (Exception e) {
            throw new SQLException(e);
        }
    }

    public DataSourceType getDataSourceType() {
        return dataSourceType;
    }

    public void setDataSourceType(DataSourceType dataSourceType) {
        this.dataSourceType = dataSourceType;
    }

    public void setDataSourceType(String dataSourceType) {
        this.dataSourceType = DataSourceType.valueOf(dataSourceType);
    }

    public DBType getDbType() {
        return dbType;
    }

    /**
     * 销毁数据源，慎用
     * 
     * @throws Exception
     */
    public void destroyDataSource() throws Exception {
        if (configManager != null) {
            configManager.destroyDataSource();
        }
    }

	@Override
	public Logger getParentLogger() throws SQLFeatureNotSupportedException {
		// TODO Auto-generated method stub
		return null;
	}

}
