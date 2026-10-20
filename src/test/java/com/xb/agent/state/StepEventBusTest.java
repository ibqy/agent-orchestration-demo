package com.xb.agent.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 步骤事件总线测试 —— SSE 的数据源
 *
 * 重点验证 replay 语义：迟到订阅者也能拿到历史事件
 * （客户端断线重连、轮询跳转全靠这个）。
 *
 * @author ibqy
 */
@DisplayName("StepEventBus 步骤事件总线")
class StepEventBusTest {

    private final StepEventBus bus = new StepEventBus();

    @Test
    @DisplayName("订阅者实时收到发布的事件")
    void subscriberReceivesLiveEvents() {
        bus.register("t1");

        var received = new java.util.concurrent.CopyOnWriteArrayList<StepEventBus.StepEvent>();
        bus.subscribe("t1").subscribe(received::add);

        bus.publish("t1", "TASK", "任务启动");
        bus.publish("t1", "THOUGHT", "思考中");
        bus.complete("t1");

        assertThat(received).hasSize(2);
        assertThat(received.get(0).type()).isEqualTo("TASK");
        assertThat(received.get(1).message()).isEqualTo("思考中");
    }

    @Test
    @DisplayName("迟到订阅者收到历史回放（replay 语义）")
    void lateSubscriberGetsReplay() {
        bus.register("t1");
        bus.publish("t1", "TASK", "任务启动");
        bus.publish("t1", "RESULT", "完成");
        bus.complete("t1");

        var received = new java.util.concurrent.CopyOnWriteArrayList<StepEventBus.StepEvent>();
        bus.subscribe("t1").subscribe(received::add);

        assertThat(received).hasSize(2);
        assertThat(received.get(1).type()).isEqualTo("RESULT");
    }

    @Test
    @DisplayName("任务不存在时订阅收到 NOT_FOUND 后结束（不挂起连接）")
    void unknownTaskGetsNotFound() {
        var received = new java.util.concurrent.CopyOnWriteArrayList<StepEventBus.StepEvent>();
        bus.subscribe("nonexistent").subscribe(received::add);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).type()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("重复 complete 幂等；complete 后 publish 静默丢弃")
    void completeIsIdempotent() {
        bus.register("t1");
        bus.publish("t1", "TASK", "x");
        bus.complete("t1");
        bus.complete("t1");  // 不炸
        bus.publish("t1", "RESULT", "y");  // 静默丢弃

        var received = new java.util.concurrent.CopyOnWriteArrayList<StepEventBus.StepEvent>();
        bus.subscribe("t1").subscribe(received::add);
        assertThat(received).hasSize(1);
    }

    @Test
    @DisplayName("activeChannels 统计未结束的通道数")
    void activeChannelsCount() {
        bus.register("t1");
        bus.register("t2");
        bus.complete("t1");
        assertThat(bus.activeChannels()).isEqualTo(1);
    }
}
