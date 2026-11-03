package com.skhynix.chat.integration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

/** 테스트가 토픽을 처음부터 읽어 특정 key 의 레코드를 확인하는 도구(그룹 없이 assign, 커밋 없음). */
final class KafkaReads {

    private KafkaReads() {
    }

    /** key 가 일치하는 레코드를 모두 모은다. 최소 {@code atLeast} 건이 모이면 조기 종료하고, 못 모으면 있는 만큼 돌려준다. */
    static List<ConsumerRecord<String, String>> byKey(String topic, String key, int atLeast, Duration maxWait) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, Containers.kafkaBootstrapServers());
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.currentTimeMillis() + maxWait.toMillis();
            long quietUntil = 0;
            while (System.currentTimeMillis() < deadline) {
                var records = consumer.poll(Duration.ofMillis(300));
                records.forEach(r -> {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                });
                if (found.size() >= atLeast && records.isEmpty()) {
                    // 더 올 것이 없는지 한 번 더 확인하고 끝낸다
                    if (quietUntil == 0) {
                        quietUntil = System.currentTimeMillis() + 600;
                    } else if (System.currentTimeMillis() > quietUntil) {
                        break;
                    }
                }
            }
        }
        return found;
    }

    /** 현재 key 의 레코드 수(최대 {@code maxWait} 동안 끝까지 읽는다). */
    static int count(String topic, String key) {
        return byKey(topic, key, Integer.MAX_VALUE, Duration.ofMillis(3500)).size();
    }
}
