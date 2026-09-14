package com.xb.agent.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;

import java.util.EnumSet;

/**
 * FSM 配置 —— Spring Statemachine
 *
 * 心得：状态机最大价值是把"什么状态下允许做什么"显式化。
 * 以前用 if-else 判断状态，漏掉一个分支就出灵异 bug；
 * 状态机把非法流转直接在框架层拒绝掉
 */
@Configuration
@EnableStateMachineFactory
public class StateMachineConfig extends StateMachineConfigurerAdapter<AgentState, AgentEvent> {

    private static final Logger log = LoggerFactory.getLogger(StateMachineConfig.class);

    @Override
    public void configure(StateMachineStateConfigurer<AgentState, AgentEvent> states) throws Exception {
        states.withStates()
                .initial(AgentState.INIT)
                .states(EnumSet.allOf(AgentState.class));
    }

    @Override
    public void configure(StateMachineTransitionConfigurer<AgentState, AgentEvent> transitions) throws Exception {
        transitions
                .withExternal().source(AgentState.INIT).target(AgentState.PLANNING).event(AgentEvent.START)
                .and()
                .withExternal().source(AgentState.PLANNING).target(AgentState.RUNNING).event(AgentEvent.PLAN_DONE)
                .and()
                .withExternal().source(AgentState.RUNNING).target(AgentState.REVIEW).event(AgentEvent.TASK_DONE)
                .and()
                .withExternal().source(AgentState.RUNNING).target(AgentState.FAILED).event(AgentEvent.TASK_FAILED)
                .and()
                .withExternal().source(AgentState.RUNNING).target(AgentState.WAIT_HUMAN).event(AgentEvent.NEED_HUMAN)
                .and()
                .withExternal().source(AgentState.WAIT_HUMAN).target(AgentState.RUNNING).event(AgentEvent.HUMAN_CONFIRM)
                .and()
                .withExternal().source(AgentState.WAIT_HUMAN).target(AgentState.FAILED).event(AgentEvent.HUMAN_ABORT)
                .and()
                .withExternal().source(AgentState.REVIEW).target(AgentState.SUCCESS).event(AgentEvent.REVIEW_PASS)
                .and()
                .withExternal().source(AgentState.REVIEW).target(AgentState.RUNNING).event(AgentEvent.REVIEW_REJECT);
        log.info("[FSM] 状态机配置完成: 7 状态 9 流转");
    }
}
