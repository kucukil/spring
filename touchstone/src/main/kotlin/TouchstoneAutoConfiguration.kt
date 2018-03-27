package org.abhijitsarkar.touchstone

import org.abhijitsarkar.touchstone.execution.gradle.GradleAgentImpl
import org.abhijitsarkar.touchstone.execution.gradle.GradleExecutor
import org.abhijitsarkar.touchstone.execution.gradle.GradleProperties
import org.abhijitsarkar.touchstone.execution.junit.JUnitExecutionListener
import org.abhijitsarkar.touchstone.execution.junit.JUnitExecutionSummary
import org.abhijitsarkar.touchstone.execution.junit.JUnitExecutionSummaryRepository
import org.abhijitsarkar.touchstone.execution.junit.JUnitExecutor
import org.abhijitsarkar.touchstone.execution.junit.JUnitProperties
import org.abhijitsarkar.touchstone.precondition.Teller
import org.abhijitsarkar.touchstone.precondition.VotingDecider
import org.abhijitsarkar.touchstone.precondition.VotingProperties
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory
import org.springframework.batch.core.job.builder.FlowBuilder
import org.springframework.batch.core.job.flow.support.SimpleFlow
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource
import org.springframework.data.jpa.repository.config.EnableJpaRepositories


/**
 * @author Abhijit Sarkar
 */
@Configuration
@ComponentScan
@EntityScan(basePackageClasses = [JUnitExecutionSummary::class])
@EnableJpaRepositories(basePackageClasses = [JUnitExecutionSummaryRepository::class])
@EnableBatchProcessing
@PropertySource("classpath:/touchstone.properties")
class TouchstoneAutoConfiguration(
        private val jobs: JobBuilderFactory,
        private val steps: StepBuilderFactory,
        private val touchstoneProperties: TouchstoneProperties,
        private val votingProperties: VotingProperties,
        private val junitProperties: JUnitProperties,
        private val gradleProperties: GradleProperties,
        private val repo: JUnitExecutionSummaryRepository
) {
    @Bean
    fun junitExecutionStep(): Step {
        return steps.get("execute-junit")
                .tasklet(JUnitExecutor(junitProperties))
                .allowStartIfComplete(touchstoneProperties.restartCompletedStep)
                .listener(JUnitExecutionListener(repo))
                .build()
    }

    @Bean
    fun gradleExecutionStep(): Step {
        return steps.get("execute-gradle")
                .tasklet(GradleExecutor(GradleAgentImpl(gradleProperties)))
                .allowStartIfComplete(touchstoneProperties.restartCompletedStep)
                .build()
    }

    @Bean
    fun teller() = Teller(votingProperties)

    @Bean
    fun tellerStep(): Step {
        return steps.get("count-votes")
                .tasklet(teller())
                .allowStartIfComplete(touchstoneProperties.restartCompletedStep)
                .build()
    }

    @Bean
    fun job(): Job {
        val testExecutorDecider = TestExecutorDecider(touchstoneProperties)

        val votingDecider = VotingDecider(votingProperties)

        val votingFlow = FlowBuilder<SimpleFlow>("voting-flow")
                .start(votingDecider)
                .on("SKIPPED").end()
                .on("CONTINUE").to(tellerStep())
                .end()

        val testingFlow = FlowBuilder<SimpleFlow>("testing-flow")
                .start(testExecutorDecider)
                .on(TestExecutor.JUNIT.name).to(junitExecutionStep())
                .from(testExecutorDecider).on(TestExecutor.GRADLE.name).to(gradleExecutionStep())
                .end()

        return jobs.get(touchstoneProperties.jobName)
                .start(votingFlow)
                .next(testingFlow)
                .end()
                .build()
    }
}