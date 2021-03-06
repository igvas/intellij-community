// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.application.impl

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.AppUIExecutor
import com.intellij.openapi.application.impl.AsyncExecutionSupport.Companion.cancelJobOnDisposal
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.experimental.*
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.CancellablePromise
import java.util.concurrent.Callable
import kotlin.coroutines.experimental.ContinuationInterceptor
import kotlin.coroutines.experimental.CoroutineContext

/**
 * @author eldar
 */
interface AppUIExecutorEx : AppUIExecutor, AsyncExecution<AppUIExecutorEx> {

  override fun execute(command: Runnable) {
    // Note, that launch() is different from async() used by submit():
    //
    //   - processing of async() errors thrown by the command are deferred
    //     until the Deferred.await() is called on the result,
    //
    //   - errors thrown within launch() are not caught, and usually result in an error
    //     message with a stack trace to be logged on the corresponding thread.
    //
    GlobalScope.launch(coroutineDispatchingContext()) {
      command.run()
    }
  }

  override fun submit(task: Runnable): CancellablePromise<*> {
    return submit<Any> {
      task.run()
    }
  }

  override fun <T> submit(task: Callable<T>): CancellablePromise<T> {
    val deferred = GlobalScope.async(coroutineDispatchingContext()) {
      task.call()
    }
    return AsyncPromise<T>().apply {
      onError { cause -> deferred.cancel(cause) }
      deferred.invokeOnCompletion {
        try {
          val result = deferred.getCompleted()
          setResult(result)
        }
        catch (e: Throwable) {
          setError(e)
        }
      }
    }
  }

  fun inUndoTransparentAction(): AppUIExecutor
  fun inWriteAction(): AppUIExecutor
}

fun AppUIExecutor.inUndoTransparentAction() =
  (this as AppUIExecutorEx).inUndoTransparentAction()
fun AppUIExecutor.inWriteAction() =
  (this as AppUIExecutorEx).inWriteAction()
fun AppUIExecutor.withConstraint(constraint: AsyncExecution.ContextConstraint): AppUIExecutor =
  (this as AppUIExecutorEx).withConstraint(constraint)

/**
 * A [context][CoroutineContext] to be used with the standard [launch], [async], [withContext] coroutine builders.
 * Contains: [ContinuationInterceptor] + [CoroutineExceptionHandler] + [CoroutineName].
 */
fun AppUIExecutor.coroutineDispatchingContext(): CoroutineContext =
  (this as AsyncExecution<*>).coroutineDispatchingContext()


@Throws(CancellationException::class)
suspend fun <T> CoroutineScope.runUnlessDisposed(disposable: Disposable, block: suspend () -> T): T {
  if (Disposer.isDisposed(disposable)) throw AsyncExecutionSupport.DisposedException(disposable)
  val context = this.coroutineContext
  val childJob = Job(context[Job])
  return disposable.cancelJobOnDisposal(childJob).use {
    withContext(context + childJob) {
      block()
    }
  }
}
