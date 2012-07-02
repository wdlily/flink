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

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import eu.stratosphere.nephele.event.task.AbstractTaskEvent;
import eu.stratosphere.nephele.io.AbstractRecordWriter;
import eu.stratosphere.nephele.io.Writer;
import eu.stratosphere.nephele.services.memorymanager.DataInputView;
import eu.stratosphere.nephele.services.memorymanager.MemorySegment;
import eu.stratosphere.pact.common.generic.types.TypeSerializer;
import eu.stratosphere.pact.common.stubs.Collector;
import eu.stratosphere.pact.common.stubs.Stub;
import eu.stratosphere.pact.common.type.PactRecord;
import eu.stratosphere.pact.common.util.MutableObjectIterator;
import eu.stratosphere.pact.runtime.io.InputViewIterator;
import eu.stratosphere.pact.runtime.iterative.concurrent.*;
import eu.stratosphere.pact.runtime.iterative.event.EndOfSuperstepEvent;
import eu.stratosphere.pact.runtime.iterative.event.TerminationEvent;
import eu.stratosphere.pact.runtime.iterative.io.SerializedUpdateBuffer;
import eu.stratosphere.pact.runtime.task.PactTaskContext;
import eu.stratosphere.pact.runtime.task.util.PactRecordOutputCollector;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;
import java.util.List;

public class BulkIterationHeadPactTask<S extends Stub, OT> extends AbstractIterativePactTask<S, OT>
    implements PactTaskContext<S, OT> {

  private static final int ITERATION_INPUT = 0;
  private static final Log log = LogFactory.getLog(BulkIterationHeadPactTask.class);

  /**
   * the iteration head prepares the backchannel: it allocates memory, instantiates a {@link BlockingBackChannel} and
   * hands it to the iteration tail via a {@link Broker} singleton
   **/
  private BlockingBackChannel initBackChannel() throws Exception {

    // compute the size of the memory available to the backchannel
    long completeMemorySize = getTaskConfig().getMemorySize();
    //TODO make this configurable!
    long backChannelMemorySize = (long) (completeMemorySize * 0.8);
    getTaskConfig().setMemorySize(completeMemorySize - backChannelMemorySize);

    // allocate the memory available to the backchannel
    List<MemorySegment> segments = Lists.newArrayList();
    int segmentSize = getMemoryManager().getPageSize();
    getMemoryManager().allocatePages(this, segments, backChannelMemorySize);

    // instantiate the backchannel
    BlockingBackChannel backChannel = new BlockingBackChannel(new SerializedUpdateBuffer(segments, segmentSize,
        getIOManager()));

    // hand the backchannel over to the iteration tail
    Broker<BlockingBackChannel> broker = BlockingBackChannelBroker.instance();
    broker.handIn(identifier(), backChannel);

    return backChannel;
  }

  @Override
  public void invoke() throws Exception {

    int numIterations = 0;

    /** used for receiving the current iteration result from iteration tail */
    BlockingBackChannel backChannel = initBackChannel();

    Broker<SuperstepBarrier> superstepBarrierBroker = SuperstepBarrierBroker.instance();


    TypeSerializer serializer = getInputSerializer(ITERATION_INPUT);
    //TODO type safety
    output = (Collector<OT>) iterationCollector();

    while (numIterations < 3) {

      log.info("Head: starting iteration [" + numIterations + "] [" + System.currentTimeMillis() + "]");

      if (numIterations > 0) {
        // reinstantiate driver
        reinstantiateDriver();
      }

      SuperstepBarrier superstepBarrier = superstepBarrierBroker.get(identifier());

      super.invoke();
      // signal to connected tasks that we are done with the superstep
      sendEventToAllIterationOutputsAndSync(new EndOfSuperstepEvent());

      // blocking call to wait for the result
      DataInputView superStepResult = backChannel.getReadEndAfterSuperstepEnded();
      log.info("finishing iteration [" + numIterations + "] [" + System.currentTimeMillis() + "]");

      log.info("waiting for other works in iteration [" + numIterations + "] [" + System.currentTimeMillis() + "]");
      superstepBarrier.waitForOthers();

      feedBackSuperstepResult(superStepResult, serializer);

      numIterations++;
    }
    // signal to connected tasks that the iteration terminated
    sendEventToAllIterationOutputsAndSync(new TerminationEvent());

    log.info("streaming out final result [" + numIterations + "] [" + System.currentTimeMillis() + "]");
    streamOutFinalOutput();
  }

  // send output to all but the last connected task while iterating
  private Collector<PactRecord> iterationCollector() {
    int numOutputs = eventualOutputs.size();
    Preconditions.checkState(numOutputs > 2);
    List<AbstractRecordWriter<PactRecord>> writers = Lists.newArrayListWithCapacity(numOutputs - 1);
    for (int outputIndex = 0; outputIndex < numOutputs - 2; outputIndex++) {
      //TODO type safety
      writers.add((AbstractRecordWriter<PactRecord>) eventualOutputs.get(outputIndex));
    }
    return new PactRecordOutputCollector(writers);
  }

  private void streamOutFinalOutput() throws IOException, InterruptedException {
    //TODO type safety
    Writer<PactRecord> writer = (AbstractRecordWriter<PactRecord>) eventualOutputs.get(eventualOutputs.size() - 1);
    MutableObjectIterator<PactRecord> results = (MutableObjectIterator<PactRecord>) inputs[ITERATION_INPUT];

    PactRecord record = new PactRecord();
    while (results.next(record)) {
      writer.emit(record);
    }
  }

  private void feedBackSuperstepResult(DataInputView superStepResult, TypeSerializer serializer) {
    inputs[ITERATION_INPUT] = new InputViewIterator(superStepResult, serializer);
  }

  private void sendEventToAllIterationOutputsAndSync(AbstractTaskEvent event) throws IOException, InterruptedException {
    //TODO remove implicit assumption
    for (int outputIndex = 0; outputIndex < eventualOutputs.size() - 1; outputIndex++) {
      flushAndPublishEvent(eventualOutputs.get(outputIndex), event);
    }
  }

}
