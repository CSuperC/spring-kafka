/*
 * Copyright 2019-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.kafka.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ContainerProperties.EOSMode;
import org.springframework.kafka.support.serializer.DeserializationException;

/**
 * @author Gary Russell
 * @since 2.3.1
 *
 */
public class DefaultAfterRollbackProcessorTests {

	@Test
	public void testClassifier() {
		AtomicReference<ConsumerRecord<?, ?>> recovered = new AtomicReference<>();
		AtomicBoolean recovererShouldFail = new AtomicBoolean(false);
		@SuppressWarnings("unchecked")
		KafkaOperations<String, String> template = mock(KafkaOperations.class);
		given(template.isTransactional()).willReturn(true);
		DefaultAfterRollbackProcessor<String, String> processor = new DefaultAfterRollbackProcessor<>((r, t) -> {
			if (recovererShouldFail.getAndSet(false)) {
				throw new RuntimeException("test recoverer failure");
			}
			recovered.set(r);
		}, SeekUtils.DEFAULT_BACK_OFF, template, true);
		ConsumerRecord<String, String> record1 = new ConsumerRecord<>("foo", 0, 0L, "foo", "bar");
		ConsumerRecord<String, String> record2 = new ConsumerRecord<>("foo", 1, 1L, "foo", "bar");
		List<ConsumerRecord<String, String>> records = Arrays.asList(record1, record2);
		IllegalStateException illegalState = new IllegalStateException();
		@SuppressWarnings("unchecked")
		Consumer<String, String> consumer = mock(Consumer.class);
		given(consumer.groupMetadata()).willReturn(new ConsumerGroupMetadata("foo"));
		processor.process(records, consumer, illegalState, true, EOSMode.ALPHA);
		processor.process(records, consumer, new DeserializationException("intended", null, false, illegalState), true,
				EOSMode.ALPHA);
		verify(template).sendOffsetsToTransaction(anyMap());
		verify(template, never()).sendOffsetsToTransaction(anyMap(), any(ConsumerGroupMetadata.class));
		assertThat(recovered.get()).isSameAs(record1);
		processor.addNotRetryableException(IllegalStateException.class);
		recovered.set(null);
		recovererShouldFail.set(true);
		processor.process(records, consumer, illegalState, true, EOSMode.ALPHA);
		verify(template, times(1)).sendOffsetsToTransaction(anyMap()); // recovery failed
		processor.process(records, consumer, illegalState, true, EOSMode.BETA);
		verify(template, times(1)).sendOffsetsToTransaction(anyMap(), any(ConsumerGroupMetadata.class));
		assertThat(recovered.get()).isSameAs(record1);
		InOrder inOrder = inOrder(consumer);
		inOrder.verify(consumer).seek(new TopicPartition("foo", 0), 0L); // not recovered so seek
		inOrder.verify(consumer, times(2)).seek(new TopicPartition("foo", 1), 1L);
		inOrder.verify(consumer).seek(new TopicPartition("foo", 0), 0L); // recovery failed
		inOrder.verify(consumer, times(2)).seek(new TopicPartition("foo", 1), 1L);
		inOrder.verify(consumer).groupMetadata();
		inOrder.verifyNoMoreInteractions();
	}

}