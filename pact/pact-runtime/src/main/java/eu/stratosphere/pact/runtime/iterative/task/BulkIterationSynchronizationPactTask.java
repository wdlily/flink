/***********************************************************************************************************************
 *
 * Copyright (C) 2012 by the Stratosphere project (http://stratosphere.eu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 **********************************************************************************************************************/

package eu.stratosphere.pact.runtime.iterative.task;

import eu.stratosphere.pact.common.stubs.Stub;
import eu.stratosphere.pact.runtime.iterative.concurrent.Broker;
import eu.stratosphere.pact.runtime.iterative.concurrent.SuperstepBarrier;
import eu.stratosphere.pact.runtime.iterative.concurrent.SuperstepBarrierBroker;
import eu.stratosphere.pact.runtime.iterative.event.Callback;
import eu.stratosphere.pact.runtime.iterative.event.EndOfSuperstepEvent;
import eu.stratosphere.pact.runtime.iterative.event.TerminationEvent;
import eu.stratosphere.pact.runtime.task.util.ReaderInterruptionBehavior;
import eu.stratosphere.pact.runtime.task.util.ReaderInterruptionBehaviors;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class BulkIterationSynchronizationPactTask<S extends Stub, OT> extends AbstractIterativePactTask<S, OT> {

  private boolean terminated = false;
  private int numIterations = 0;

  private SuperstepBarrier superstepBarrier;

  private static final Log log = LogFactory.getLog(BulkIterationSynchronizationPactTask.class);

  @Override
  protected ReaderInterruptionBehavior readerInterruptionBehavior() {
    return ReaderInterruptionBehaviors.FALSE_ON_INTERRUPT;
  }

  @Override
  public void invoke() throws Exception {

    listenToEndOfSuperstep(new Callback<EndOfSuperstepEvent>() {
      @Override
      public void execute(EndOfSuperstepEvent event) throws Exception {
        log.info("received endOfSuperStep [" + System.currentTimeMillis() + "]");
        superstepBarrier.signalWorkerDone();
      }
    });

    listenToTermination(new Callback<TerminationEvent>() {
      @Override
      public void execute(TerminationEvent event) throws Exception {
        log.info("received termination [" + System.currentTimeMillis() + "]");
        terminated = true;
      }
    });

    Broker<SuperstepBarrier> superstepBarrierBroker = SuperstepBarrierBroker.instance();
    int numSubtasks = getEnvironment().getCurrentNumberOfSubtasks();

    while (!terminated) {

      log.info("starting iteration [" + numIterations + "] [" + System.currentTimeMillis() + "]");
      if (numIterations > 0) {
        reinstantiateDriver();
      }

      superstepBarrier = new SuperstepBarrier(numSubtasks);
      superstepBarrierBroker.handIn(identifier(), superstepBarrier);

      super.invoke();

      log.info("finishing iteration [" + numIterations + "] [" + System.currentTimeMillis() + "]");
      numIterations++;
    }
  }
}
