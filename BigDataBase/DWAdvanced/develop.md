### 总线矩阵

<https://mp.weixin.qq.com/s?__biz=Mzg3NjIyNjQwMg==&mid=2247519639&idx=1&sn=ee88c2dbaa7e84d0ce49c008677eec77>

实际设计过程中，我们通常把总线架构列表成矩阵的形式，其中列为一致性维度，行为不同的业务处理过程，即事实。
在交叉点上打上标记表示该业务处理过程与该维度相关，这个矩阵也称为总线矩阵（Bus Matrix）。

![zongxianjuzhen](https://mmbiz.qpic.cn/mmbiz_png/zWSuIP8rdu2aqlXlq9HyI3jOfRqRgHtVuzMicBH7LsTEP7nPRjib5FrubvQQOkxO5Tx3bqys3abUeVVfJ0yOoYag/640?wx_fmt=png&wxfrom=5&wx_lazy=1&wx_co=1)

总线矩阵和一致性维度、一致性事实共同组成了 Kimball 的多维体系结构基础，
如何设计总线矩阵？

1. 首先完成横向，即数据域划分；
2. 其次完成纵向，即一致性公共列维度的划分以及度量值的确定。

### 全量和增量数据同步策略

1. 上周期全量 union all 本周期增量 基于主键对更新时间排序 --> 获取最新的全量数据（常规的，目前正在使用）
时延高、吞吐大

2. 结合Flink CDC，基于Mysql的binlog实时记录收集数据新增、更新等信息，实时更新数据到最新状态。
结合Flink CDC，基于Mysql的binlog实时记录收集数据新增、更新等信息，实时更新数据到最新状态。
    在初始化时，以离线模式批量从数据库中拉取全量数据，初始化到Hudi表中；订阅数据库的增量数据，增量更新到Hudi表中。数据以分钟级的延迟和数据库保持完全一致。

    ![pic](https://mmbiz.qpic.cn/mmbiz_png/1BMf5Ir754RaM96aWZJbc3JW5dsDzZBiaAic7zx4YW7EoM5cFbu5med86GtRCuic0NFhQjBFOZ6nQ7XYqq9pYzqxA/640?wx_fmt=png&wxfrom=5&wx_lazy=1&wx_co=1)

3. 拉链表 + Hudi

    Hudi+Flink CDC用于支持新型准实时需求类需求，对于时效性要求高的需求，比如需要分钟级的延迟，以Hudi+Flink CDC进行支持。

    拉链表方案做存量全量分区表的无缝迁移，和支持离线T-1类的时效性要求较低的需求，以及需要历史所有变更的全版本下的支持。

![拉链表](https://mmbiz.qpic.cn/mmbiz_png/1BMf5Ir754RaM96aWZJbc3JW5dsDzZBiag4lcNkoxDMFvMOibwR7aBvbjx6eZ26JwwEDjbZAD2287zsXIRK3jibMA/640?wx_fmt=png&wxfrom=5&wx_lazy=1&wx_co=1)

伪代码

```sql
insert overwrite table 拉链表
select n1.id, n1.昵称,n1.start_date 
case when 
n1.start_date = "9999-12-31" 
and n2.id is not null 
then "业务时间-1" 
else n1.end_date
end as end_date
from 拉链表 n1
left outer join 
(select id from 用户表 where 昨日新注册 or 昨日变更昵称) n2 
on n1.id = n2.id
union all
select id,昵称，"业务日期" as start_date,"9999-12-31" as end_date 
from 用户表
where 昨日新注册 or 昨日变更昵称
```

### 拉链表的设计

<https://developer.aliyun.com/article/542146>

一个例子

```sql

-- 用法：
-- 查询当前的所有有效record select * from temp_mj_info_his where end_date = '99992131'
CREATE TABLE IF NOT EXISTS rcmd_odps_deep_dev.temp_mj_info_delta
(
    uid string
    ,address string
    ,create_date STRING
)
PARTITIONED BY 
(
    ds STRING COMMENT '分区'
)
LIFECYCLE 1
;

CREATE TABLE IF NOT EXISTS rcmd_odps_deep_dev.temp_mj_info_his 
(
    uid STRING
    ,address string
    ,create_date STRING COMMENT '创建时间'
    ,start_date STRING COMMENT '生命周期开始时间'
    ,end_date STRING COMMENT '生命周期结束时间'
)
LIFECYCLE 1
;

-- -- 以20200101为第一天，并且已经初始化好
-- INSERT OVERWRITE TABLE rcmd_odps_deep_dev.temp_mj_info_his VALUES ('u01','d01','20200101'，'20200101','99992131'),('u02','d02','20200101','20200101','99992131'),('u03','d03','20200101','20200101','99992131') ;

-- 事件：
-- 20200102，u01用户更新了地址到a01，u04是新增用户d04
-- 20200103，u04用户更新了地址到a04，u05、u06是新增用户d05、d06

-- 20200101的增量数据
INSERT OVERWRITE TABLE rcmd_odps_deep_dev.temp_mj_info_delta PARTITION(ds = '20200101') VALUES ('u01','d01','20200101'),('u02','d02','20200101'),('u03','d03','20200101');
-- 20200102的增量数据
INSERT OVERWRITE TABLE rcmd_odps_deep_dev.temp_mj_info_delta  PARTITION(ds='20200102') values ('u01','a01','20200102'),('u04','d04','20200102');
-- 20200103的增量数据
INSERT OVERWRITE TABLE rcmd_odps_deep_dev.temp_mj_info_delta  PARTITION(ds='20200103') values ('u04','a04','20200103'),('u06','d06','20200103'),('u05','d05','20200103');

INSERT OVERWRITE TABLE rcmd_odps_deep_dev.temp_mj_info_his
SELECT  uid
        ,address
        ,create_date
        ,start_date
        ,end_date
FROM    (
            SELECT  t1.uid
                    ,t1.address
                    ,t1.create_date
                    ,t1.start_date
                    ,case    WHEN t1.end_date = '99991231' AND t2.uid IS NOT NULL THEN t2.ds 
                             ELSE t1.end_date 
                     END AS end_date
            FROM    (
                        SELECT  uid
                                ,address
                                ,create_date
                                ,start_date
                                ,end_date
                        FROM    rcmd_odps_deep_dev.temp_mj_info_his
                    ) t1
            LEFT join (
                          SELECT  uid
                                  ,ds
                          FROM    rcmd_odps_deep_dev.temp_mj_info_delta
                          WHERE   ds = '${date}'
                      ) t2
            ON      t1.uid = t2.uid
            UNION
            SELECT  uid
                    ,address
                    ,create_date
                    ,'${date}' AS start_date
                    ,'99991231' AS end_date
            FROM    rcmd_odps_deep_dev.temp_mj_info_delta
            WHERE   ds = '${date}'
        ) 
;
```

### 3O-写这里是怕忘记了

- override 覆盖：子类继承了父类的同名无参函数，子类信息的的方法覆盖父类的方法。 （继承）
- overload 重载：子类继承了父类的同名有参函数，但参数列表不同即为重载。（继承）
- overwrite 重写：当前类的同名方法，参数列表不同即为重写。（当前类）

### Flink CDC Documents

<https://ververica.github.io/flink-cdc-connectors/release-2.2/content/about.html>
<https://ucc-private-download.oss-cn-beijing.aliyuncs.com/98313cc2b91c46338e789a898fd37c66.pdf?Expires=1652959769&OSSAccessKeyId=LTAIvsP3ECkg4Nm9&Signature=aAwMO8rod%2FA3iLBxJcrFKSaY0ZU%3D>

### Flink Batch_Streaming Execution Mode Explain

<https://nightlies.apache.org/flink/flink-docs-master/zh/docs/dev/datastream/execution_mode/>

### 数据分析师都了解的基本统计概念

<https://mp.weixin.qq.com/s?__biz=MjM5MDI1ODUyMA==&mid=2672963006&idx=1&sn=554d0742055e2679673c2a227d2aca32>