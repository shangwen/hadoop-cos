# HADOOP-COS

## 功能说明
Hadoop-COS插件实现了以腾讯云 COS 作为底层文件系统运行上层计算任务的功能，支持使用Hadoop、Spark以及Tez等处理存储在腾讯云COS对象存储系统上的数据。

## 使用限制
只适用于 COS V5 版本

## 使用环境
### 系统环境
Linux 或 Windows 系统

### 软件依赖
Hadoop-2.6.0及以上版本

## 安装方法

### 获取 hadoop-cos 插件
下载地址：[hadoop-cos 插件](https://github.com/tencentyun/hadoop-cos)


### 安装hadoop-cos插件

1. 将dep目录下的cos_hadoop_api-5.2.6.jar 和 hadoop-cos-2.X.X.jar 拷贝到 `$HADOOP_HOME/share/hadoop/tools/lib`下。

NOTE: 根据hadoop的具体版本选择对应的jar包，若dep目录中没有提供匹配版本的jar包，可自行通过修改pom文件中hadoop版本号，重新编译生成。

2. 修改 hadoop_env.sh
在 `$HADOOP_HOME/etc/hadoop`目录下，进入 hadoop_env.sh，增加如下内容，将 cosn 相关 jar 包加入 Hadoop 环境变量：

```shell
for f in $HADOOP_HOME/share/hadoop/tools/lib/*.jar; do
  if [ "$HADOOP_CLASSPATH" ]; then
    export HADOOP_CLASSPATH=$HADOOP_CLASSPATH:$f
  else
    export HADOOP_CLASSPATH=$f
  fi
done
```

## 使用方法

### HADOOP配置

修改 $HADOOP_HOME/etc/hadoop/core-site.xml，增加 COS 相关用户和实现类信息，例如：

```xml
<configuration>

    <property>
        <name>fs.defaultFS</name>
        <value>cosn://examplebucket-1250000000000</value>
    </property>

    <property>
        <name>fs.cosn.credentials.provider</name>
        <value>org.apache.hadoop.fs.auth.SimpleCredentialProvider</value>
        <description>

            This option allows the user to specify how to get the credentials.
            Comma-separated class names of credential provider classes which implement
            com.qcloud.cos.auth.COSCredentialsProvider:

            1.org.apache.hadoop.fs.auth.SessionCredentialProvider: Obtain the secretId and secretKey from the URI:cosn://secretId:secretKey@example-1250000000000/;
            2.org.apache.hadoop.fs.auth.SimpleCredentialProvider: Obtain the secret id and secret key
            from fs.cosn.userinfo.secretId and fs.cosn.userinfo.secretKey in core-site.xml;
            3.org.apache.hadoop.fs.auth.EnvironmentVariableCredentialProvider: Obtain the secret id and secret key
            from system environment variables named COS_SECRET_ID and COS_SECRET_KEY.

            If unspecified, the default order of credential providers is:
            1. org.apache.hadoop.fs.auth.SessionCredentialProvider
            2. org.apache.hadoop.fs.auth.SimpleCredentialProvider
            2. org.apache.hadoop.fs.auth.EnvironmentVariableCredentialProvider

        </description>
    </property>

    <property>
        <name>fs.cosn.userinfo.secretId</name>
        <value>xxxxxxxxxxxxxxxxxxxxxxxxx</value>
        <description>Tencent Cloud Secret Id</description>
    </property>

    <property>
        <name>fs.cosn.userinfo.secretKey</name>
        <value>xxxxxxxxxxxxxxxxxxxxxxxx</value>
        <description>Tencent Cloud Secret Key</description>
    </property>

    <property>
        <name>fs.cosn.bucket.region</name>
        <value>ap-xxx</value>
        <description>The region where the bucket is located</description>
    </property>

    <property>
        <name>fs.cosn.bucket.endpoint_suffix</name>
        <value>cos.ap-xxx.myqcloud.com</value>
        <description>COS endpoint to connect to.
        For public cloud users, it is recommended not to set this option, and only the correct area field is required.</description>
    </property>

    <property>
        <name>fs.cosn.impl</name>
        <value>org.apache.hadoop.fs.CosFileSystem</value>
        <description>The implementation class of the CosN Filesystem</description>
    </property>

    <property>
        <name>fs.AbstractFileSystem.cosn.impl</name>
        <value>org.apache.hadoop.fs.CosN</value>
        <description>The implementation class of the CosN AbstractFileSystem.</description>
    </property>

    <property>
        <name>fs.cosn.tmp.dir</name>
        <value>/tmp/hadoop_cos</value>
        <description>Temporary files will be placed here.</description>
    </property>

    <property>
    	<name>fs.cosn.upload.buffer</name>
        <value>mapped_disk</value>
        <description>The type of upload buffer. Available values: non_direct_memory, direct_memory, mapped_disk</description>
    </property>

    <property>
    	<name>fs.cosn.upload.buffer.size</name>
        <value>33554432</value>
        <description>The total size of the upload buffer pool. -1 means unlimited.</description>
    </property>

    <property>
    	<name>fs.cosn.block.size</name>
        <value>8388608</value>
        <description>Block size to use cosn filesysten, which is the part size for MultipartUpload.
        Considering the COS supports up to 10000 blocks, user should estimate the maximum size of a single file.
        for example, 8MB part size can allow  writing a 78GB single file.</description>
    </property>

    <property>
    	<name>fs.cosn.maxRetries</name>
        <value>3</value>
        <description>The maximum number of retries for reading or writing files to
    COS, before we signal failure to the application.</description>
    </property>

    <property>
    	<name>fs.cosn.retry.interval.seconds</name>
        <value>3</value>
        <description>The number of seconds to sleep between each COS retry.</description>
    </property>

    <property>
    	<name>fs.cosn.read.ahead.block.size</name>
        <value>‭1048576‬</value>
        <description>
        	Bytes to read ahead during a seek() before closing and
            re-opening the cosn HTTP connection.
        </description>
    </property>

	<property>
    	<name>fs.cosn.read.ahead.queue.size</name>
        <value>8</value>
        <description>The length of the pre-read queue.</description>
    </property>

</configuration>

```

**配置项说明**：

| 属性键                             | 说明                | 默认值 | 必填项 |
|:-----------------------------------:|:--------------------|:-----:|:---:|
|fs.defaultFS                       | 配置hadoop默认使用的底层文件系统，如果想使用cos作为hadoop默认文件系统，则此项应设置为cosn://bucket-appid，此时可以通过文件路径访问cos对象，如/hadoop/inputdata/test.dat。若不想把cos作为hadoop默认文件系统，则不需要修改此项，当需要访问cos上的对象时，则指定完整的uri即可，如cosn://testbucket-1252681927/hadoop/inputdata/test.dat来访问。
|fs.cosn.credentials.provider       |配置secret id和secret key的获取方式。当前支持三种获取方式：1.org.apache.hadoop.fs.auth.SessionCredentialProvider：从请求URI中获取secret id和secret key，其格式为：cosn://{secretId}:{secretKey}@examplebucket-1250000000000/; 2.org.apache.hadoop.fs.auth.SimpleCredentialProvider：从core-site.xml配置文件中读取fs.cosn.userinfo.secretId和fs.cosn.userinfo.secretKey来获取secret id和secret key; 3.org.apache.hadoop.fs.auth.EnvironmentVariableCredentialProvider：从系统环境变量COS_SECRET_ID和COS_SECRET_KEY中获取。|如果不指定改配置项，默认会按照以下顺序读取：1.org.apache.hadoop.fs.auth.SessionCredentialProvider; 2.org.apache.hadoop.fs.auth.SimpleCredentialProvider；3.org.apache.hadoop.fs.auth.EnvironmentVariableCredentialProvider。|否|
|fs.cosn.useHttps|配置是否使用https协议。|false|否|
|fs.cosn.bucket.endpoint_suffix|指定要连接的COS endpoint，该项为非必填项目。对于公有云COS用户而言，只需要正确填写上述的region配置即可。兼容原配置项：fs.cosn.userinfo.endpoint_suffix。|无|否|
|fs.cosn.userinfo.secretId/secretKey| 填写您账户的API 密钥信息。可通过 [云 API 密钥 控制台](https://console.cloud.tencent.com/capi) 查看。| 无  | 是|
|fs.cosn.impl                      | cosn对FileSystem的实现类，固定为 org.apache.hadoop.fs.CosFileSystem。| 无|是|
|fs.AbstractFileSystem.cosn.impl   | cosn对AbstractFileSy stem的实现类，固定为org.apache.hadoop.fs.CosN。| 无 |是|
|fs.cosn.bucket.region           | 请填写您的地域信息，枚举值为 [可用地域](https://cloud.tencent.com/document/product/436/6224) 中的地域简称，如ap-beijing、ap-guangzhou等。 兼容原配置项：fs.cosn.userinfo.region。| 无 | 是|
|fs.cosn.tmp.dir                   | 请设置一个实际存在的本地目录，运行过程中产生的临时文件会暂时放于此处。|/tmp/hadoop_cos | 否|
|fs.cosn.upload.buffer             | CosN文件系统上传时依赖的缓冲区类型。当前支持三种类型的缓冲区：非直接内存缓冲区（non_direct_memory），直接内存缓冲区（direct_memory），磁盘映射缓冲区（mapped_disk）。非直接内存缓冲区使用的是JVM堆内存，直接内存缓冲区使用的是堆外内存，而磁盘映射缓冲区则是基于内存文件映射得到的缓冲区。| mapped_disk | 否 |
|fs.cosn.upload.buffer.size        | CosN文件系统上传时依赖的缓冲区大小，如果指定为-1，则表示不限制。若不限制缓冲区大小，则缓冲区类型必须为mapped_disk。如果指定大小大于0，则要求该值至少大于等于一个block的大小。兼容原配置项：fs.cosn.buffer.size。|-1|否|
|fs.cosn.block.size                | CosN文件系统每个block的大小，也是分块上传的每个part size的大小。由于COS的分块上传最多只能支持10000块，因此需要预估最大可能使用到的单文件大小。例如，block size为8MB时，最大能够支持78GB的单文件上传。 block size最大可以支持到2GB，即单文件最大可支持19TB | 8388608（8MB） | 否 |
|fs.cosn.upload_thread_pool        | 文件流式上传到COS时，并发上传的线程数目 | CPU核心数*5 | 否|
|fs.cosn.copy_thread_pool 		   | 目录拷贝操作时，可用于并发拷贝文件的线程数目 | CPU核心数目*3 | 否 |
|fs.cosn.read.ahead.block.size     | 预读块的大小                                 | ‭1048576‬（1MB） |  否 |
|fs.cosn.read.ahead.queue.size     | 预读队列的长度                               | 8              | 否  |
|fs.cosn.maxRetries				   | 访问COS出现错误时，最多重试的次数 | 5 | 否 |
|fs.cosn.retry.interval.seconds    | 每次重试的时间间隔 | 3 | 否 |
|fs.cosn.max.connection.num | 配置COS连接池中维持的最大连接数目，这个数目与单机读写COS的并发有关，建议至少大于或等于单机读写COS的并发数| 1024 | 否|

### 开始使用

命令格式为：`hadoop fs -ls -R cosn://examplebucket-1250000000000/<路径>`或`hadoop fs -ls -R /<路径>`(配置了fs.defaultFS选项为 cosn://<bucket-appid> 后) ，下例中以名称为 hdfs-test-1252681929 的 bucket 为例，可在其后面加上具体路径。

```shell

hadoop fs -ls -R cosn://hdfs-test-1252681929/
-rw-rw-rw-   1 root root       1087 2018-06-11 07:49 cosn://hdfs-test-1252681929/LICENSE
drwxrwxrwx   - root root          0 1970-01-01 00:00 cosn://hdfs-test-1252681929/hdfs
drwxrwxrwx   - root root          0 1970-01-01 00:00 cosn://hdfs-test-1252681929/hdfs/2018
-rw-rw-rw-   1 root root       1087 2018-06-12 03:26 cosn://hdfs-test-1252681929/hdfs/2018/LICENSE
-rw-rw-rw-   1 root root       2386 2018-06-12 03:26 cosn://hdfs-test-1252681929/hdfs/2018/ReadMe
drwxrwxrwx   - root root          0 1970-01-01 00:00 cosn://hdfs-test-1252681929/hdfs/test
-rw-rw-rw-   1 root root       1087 2018-06-11 07:32 cosn://hdfs-test-1252681929/hdfs/test/LICENSE
-rw-rw-rw-   1 root root       2386 2018-06-11 07:29 cosn://hdfs-test-1252681929/hdfs/test/ReadMe

```

运行 MapReduce 自带的 wordcount

> <font color="#0000cc">**注意：** </font>
以下命令中 hadoop-mapreduce-examples-2.7.2.jar 是以 2.7.2 版本为例，如版本不同，请修改成对应的版本号。

```shell
bin/hadoop jar share/hadoop/mapreduce/hadoop-mapreduce-examples-2.7.2.jar wordcount cosn://examplebucket-1250000000000/mr/input cosn://examplebucket-1250000000000/mr/output3
```

执行成功会返回统计信息，示例如下：

```shell
File System Counters
        COSN: Number of bytes read=72
        COSN: Number of bytes written=40
        COSN: Number of read operations=0
        COSN: Number of large read operations=0
        COSN: Number of write operations=0
        FILE: Number of bytes read=547350
        FILE: Number of bytes written=1155616
        FILE: Number of read operations=0
        FILE: Number of large read operations=0
        FILE: Number of write operations=0
        HDFS: Number of bytes read=0
        HDFS: Number of bytes written=0
        HDFS: Number of read operations=0
        HDFS: Number of large read operations=0
        HDFS: Number of write operations=0
    Map-Reduce Framework
        Map input records=5
        Map output records=7
        Map output bytes=59
        Map output materialized bytes=70
        Input split bytes=99
        Combine input records=7
        Combine output records=6
        Reduce input groups=6
        Reduce shuffle bytes=70
        Reduce input records=6
        Reduce output records=6
        Spilled Records=12
        Shuffled Maps =1
        Failed Shuffles=0
        Merged Map outputs=1
        GC time elapsed (ms)=0
        Total committed heap usage (bytes)=653262848
    Shuffle Errors
        BAD_ID=0
        CONNECTION=0
        IO_ERROR=0
        WRONG_LENGTH=0
        WRONG_MAP=0
        WRONG_REDUCE=0
    File Input Format Counters
        Bytes Read=36
    File Output Format Counters
        Bytes Written=40
```
