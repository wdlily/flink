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
import eu.stratosphere.pact.runtime.iterative.concurrent.BlockingBackChannel;
import eu.stratosphere.pact.runtime.iterative.concurrent.BlockingBackChannelBroker;
import eu.stratosphere.pact.runtime.iterative.concurrent.Broker;
import eu.stratosphere.pact.runtime.iterative.event.Callback;
import eu.stratosphere.pact.runtime.iterative.event.TerminationEvent;
import eu.stratosphere.pact.runtime.iterative.io.DataOutputCollector;
import eu.stratosphere.pact.runtime.task.PactTaskContext;
import eu.stratosphere.pact.runtime.task.util.ReaderInterruptionBehavior;
import eu.stratosphere.pact.runtime.task.util.ReaderInterruptionBehaviors;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


public class BulkIterationTailPactTask<S extends Stub, OT> extends AbstractIterativePactTask<S, OT>
    implements PactTaskContext<S, OT> {

  private int numIterations = 0;
  private boolean terminated = false;

  private static final Log log = LogFactory.getLog(BulkIterationTailPactTask.class);

  @Override
  protected ReaderInterruptionBehavior readerInterruptionBehavior() {
    return ReaderInterruptionBehaviors.FALSE_ON_INTERRUPT;
  }

  private BlockingBackChannel retrieveBackChannel() throws Exception {
    // blocking call to retrieve the backchannel from the iteration head
    Broker<BlockingBackChannel> broker = BlockingBackChannelBroker.instance();
    return broker.get(identifier());
  }

  @Override
  public void invoke() throws Exception {

    // Initially retreive the backchannel from the iteration head
    final BlockingBackChannel backChannel = retrieveBackChannel();

    listenToTermination(new Callback<TerminationEvent>() {
      @Override
      public void execute(TerminationEvent event) throws Exception {
        log.info("received termination [" + System.currentTimeMillis() + "]");
        terminated = true;
      }
    });

    // redirect output to the backchannel
    output = new DataOutputCollector<OT>(backChannel.getWriteEnd(), createOutputTypeSerializer());

    while (!terminated) {

      log.info("starting iteration [" + numIterations + "] [" + System.currentTimeMillis() + "]");
      if (numIterations > 0) {
        reinstantiateDriver();
      }

      super.invoke();

      log.info("finishing iteration [" + numIterations + "] [" + System.currentTimeMillis() + "]");
      backChannel.notifyOfEndOfSuperstep();

      numIterations++;
    }

  }

}
