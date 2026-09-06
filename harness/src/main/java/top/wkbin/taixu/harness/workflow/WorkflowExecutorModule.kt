package top.wkbin.taixu.harness.workflow

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkflowExecutorModule {
    @Binds @IntoSet abstract fun bindPassthrough(impl: PassthroughNodeExecutor): NodeExecutor
    @Binds @IntoSet abstract fun bindApproval(impl: ApprovalNodeExecutor): NodeExecutor
    @Binds @IntoSet abstract fun bindLinux(impl: LinuxNodeExecutor): NodeExecutor
    @Binds @IntoSet abstract fun bindAgent(impl: AgentNodeExecutor): NodeExecutor

    @Binds abstract fun bindAgentExecutionPort(impl: HarnessWorkflowAgentExecutionPort): WorkflowAgentExecutionPort
}
