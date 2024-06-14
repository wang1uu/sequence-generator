package cc.wang1;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.net.NetworkInterface;
import java.util.concurrent.ThreadLocalRandom;

/**
 * SnowFlake的结构（64）：标志位（1） - 时间序列（41） - 机房标识（5） - 机器标识（5） - 序列号（12）
 * <br/>
 * 0 - 0000000000 0000000000 0000000000 0000000000 0 - 00000 - 00000 - 000000000000
 */
public class SequenceGenerator {
    private static final Logger log = LoggerFactory.getLogger(SequenceGenerator.class);

    /**
     * 时间起始标记（基准时间）
     */
    private static final long epoch = 1519740777809L;

    /**
     * 每个时间单位（毫秒内）产生的id数: 2的12次方
     */
    private final long sequenceBits = 12L;

    /**
     * 5位的机器id
     */
    private final long brokerIdBits = 5L;
    private final long maxBrokerId = ~(-1L << brokerIdBits);

    /**
     * 5位的机房id
     */
    private final long groupIdBits = 5L;
    private final long maxGroupId = ~(-1L << groupIdBits);


    /**
     * 各个部分的偏移量
     */
    private final long brokerIdBitsShift = sequenceBits;
    private final long groupIdBitsShift = sequenceBits + brokerIdBits;
    private final long timestampBitsShift = sequenceBits + brokerIdBits + groupIdBits;


    /**
     * 每个时间单位序列的最大值掩码
     */
    private final long sequenceMask = ~(-1L << sequenceBits);


    /**
     * 机房/机器组
     */
    private final long groupId;

    /**
     * 机器id
     */
    private final long brokerId;

    /**
     * 尾序列
     */
    private long sequence = 0L;

    /**
     * 上次使用的时间戳
     */
    private long lastTimestamp = -1;

    public SequenceGenerator(long groupId, long brokerId) {
        if (brokerId > maxGroupId || brokerId < 0) {
            throw new IllegalArgumentException(String.format("Broker Id can't be greater than %d or less than 0.", maxBrokerId));
        }
        if (groupId > maxGroupId || groupId < 0) {
            throw new IllegalArgumentException(String.format("GroupId Id can't be greater than %d or less than 0.", maxGroupId));
        }
        this.groupId = groupId;
        this.brokerId = brokerId;
    }

    public SequenceGenerator() {
        this.groupId = generateGroupId();
        this.brokerId = generateBrokerId(groupId);
    }

    /**
     * 基于网卡MAC地址计算余数作为数据中心
     */
    protected long generateGroupId() {
        try {
            NetworkInterface network = NetworkInterface.getByInetAddress(Util.getLocalAddress());
            if (null == network) {
                return 1;
            }
            byte[] mac = network.getHardwareAddress();
            if (null != mac) {
                long id = ((0x000000FF & (long) mac[mac.length - 2]) | (0x0000FF00 & (((long) mac[mac.length - 1]) << 8))) >> 6;
                return id % (maxGroupId + 1);
            }
        } catch (Exception e) {
            log.warn("Generate group id failed : {} , using default group id 0.", e.getMessage());
        }

        return 0;
    }

    /**
     * 基于 MAC + PID 的 hashcode 获取16个低位
     */
    protected long generateBrokerId(long groupId) {
        StringBuilder mpId = new StringBuilder();
        mpId.append(groupId);
        String name = ManagementFactory.getRuntimeMXBean().getName();
        if (name != null && !name.isEmpty()) {
            // jvm Pid
            mpId.append(name.split("@")[0]);
        }
        // MAC + PID 的 hashcode 获取16个低位
        return (mpId.toString().hashCode() & 0xffff) % (maxBrokerId + 1);
    }

    /**
     * 退避策略
     */
    protected void backoff(long millis) {
        try {
            wait(millis);
        } catch (InterruptedException ignore) {}
    }

    public synchronized long generateSequence() {
        long timestamp = SystemClock.INSTANCE.currentTimeMillis();

        // 闰秒
        while (timestamp < lastTimestamp) {
            if (timestamp - lastTimestamp > 4) {
                throw new RuntimeException(String.format("Clock moved backwards. Refusing to generate id for %d milliseconds", timestamp - lastTimestamp));
            }

            backoff((timestamp - lastTimestamp) << 1);
            timestamp = SystemClock.INSTANCE.currentTimeMillis();
        }

        // 同一时间戳
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & sequenceMask;

            // 序列被分配完，更新到下一时间戳
            while (sequence == 0 && timestamp == lastTimestamp) {
                backoff(1);
                timestamp = SystemClock.INSTANCE.currentTimeMillis();
            }
        }else {
            // 不同时间戳
            sequence = ThreadLocalRandom.current().nextLong(1, 3);
        }

        // 更新最后一次使用的时间戳
        lastTimestamp = timestamp;

        // 拼接序列
        return ((timestamp - epoch) << timestampBitsShift)
                | (groupId << groupIdBitsShift)
                | (brokerId << brokerIdBitsShift)
                | sequence;
    }
}
