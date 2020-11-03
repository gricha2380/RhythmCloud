package com.amazonaws.rhythmcloud.io;

import com.amazonaws.rhythmcloud.Constants;
import com.amazonaws.rhythmcloud.domain.DrumHitReading;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.kinesis.shaded.com.amazonaws.regions.Regions;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.timestamps.AscendingTimestampExtractor;
import org.apache.flink.streaming.connectors.kinesis.FlinkKinesisConsumer;
import org.apache.flink.streaming.connectors.kinesis.config.AWSConfigConstants;
import org.apache.flink.streaming.connectors.kinesis.config.ConsumerConfigConstants;
import org.apache.flink.streaming.connectors.kinesis.util.JobManagerWatermarkTracker;

import java.util.Map;
import java.util.Properties;

import static com.amazonaws.rhythmcloud.Constants.DEFAULT_REGION_NAME;

@Slf4j
public class Kinesis {
    public static DataStream<DrumHitReading> createSourceFromApplicationProperties(
            Constants.Stream stream,
            Map<String, Properties> applicationProperties,
            StreamExecutionEnvironment env) {
        Properties sourceProperties = applicationProperties.get(
                Constants.propertyGroupNames.get(stream));

        // Throw exception if you cannot find the source properties
        if (sourceProperties == null) {
            String errorMessage = String.format("Unable to load %s property group from the Kinesis Analytics Runtime.",
                    Constants.propertyGroupNames.get(stream));
            log.error(errorMessage);
            throw new RuntimeException(errorMessage);
        }

        // Make sure all the mandatory properties are set: REGION, STREAMPOSITION et al.
        if (sourceProperties.getProperty(AWSConfigConstants.AWS_REGION) == null) {
            //set the region the Kinesis stream is located in
            sourceProperties.put(AWSConfigConstants.AWS_REGION,
                    Regions.getCurrentRegion() == null ? DEFAULT_REGION_NAME :
                            Regions.getCurrentRegion().getName());
        }
        if (sourceProperties.getProperty(ConsumerConfigConstants.STREAM_INITIAL_POSITION) == null) {
            //stream initialization position
            sourceProperties.put(ConsumerConfigConstants.STREAM_INITIAL_POSITION,
                    Constants.STREAM_LATEST_POSITION);
        }
        if (sourceProperties.getProperty(ConsumerConfigConstants.SHARD_GETRECORDS_INTERVAL_MILLIS) == null) {
            //poll interval to poll for new events from Kinesis stream
            //default is 1 second
            sourceProperties.put(ConsumerConfigConstants.SHARD_GETRECORDS_INTERVAL_MILLIS,
                    Constants.STREAM_POLL_INTERVAL);
        }
        //obtain credentials through the DefaultCredentialsProviderChain, which includes the instance metadata
        sourceProperties.put(AWSConfigConstants.AWS_CREDENTIALS_PROVIDER, "AUTO");

        log.info("Source Properties {}", sourceProperties.toString());

        /*
        In order to work with event time, Flink needs to know the events timestamps,
        meaning each element in the stream needs to have its event timestamp assigned.
        This is usually done by accessing/extracting the timestamp from some field in
        the element by using a TimestampAssigner.

        Timestamp assignment goes hand-in-hand with generating watermarks,
        which tell the system about progress in event time.
        You can configure this by specifying a WatermarkGenerator.

        The Flink API expects a WatermarkStrategy that contains both a
        TimestampAssigner and WatermarkGenerator.

        https://www.bookstack.cn/read/Flink-1.10-en/4836c4072c95392f.md
        The FlinkKinesisConsumer is an exactly-once parallel streaming data source
        that subscribes to multiple AWS Kinesis streams within the same AWS service region,
        and can transparently handle resharding of streams while the job is running.
        Each subtask of the consumer is responsible for fetching data records
        from multiple Kinesis shards. The number of shards fetched by each subtask will
        change as shards are closed and created by Kinesis.
        If streaming topologies choose to use the event time notion for record timestamps,
        an approximate arrival timestamp will be used by default.
        This timestamp is attached to records by Kinesis once they were successfully
        received and stored by streams. Note that this timestamp is typically
        referred to as a Kinesis server-side timestamp, and there are no guarantees
        about the accuracy or order correctness. You can override this default with a
        custom timestamp
         */
        FlinkKinesisConsumer<DrumHitReading> drumHitReadingFlinkKinesisConsumer = new FlinkKinesisConsumer<>(
                sourceProperties.getProperty("input.stream.name",
                        Constants.streamNames.get(stream)),
                new DrumHitReadingDeserializer(),
                sourceProperties);

        /*
        The simplest special case for periodic watermark generation is the case where
        timestamps seen by a given source task occur in ascending order.
        In that case, the current timestamp can always act as a watermark,
        because no earlier timestamps will arrive.
         */
        drumHitReadingFlinkKinesisConsumer.setPeriodicWatermarkAssigner(new AscendingTimestampExtractor<DrumHitReading>() {
            @Override
            public long extractAscendingTimestamp(DrumHitReading drumHitReading) {
                return drumHitReading.getTimestamp();
            }
        });

        /*
        The Flink Kinesis Consumer optionally supports synchronization between
        parallel consumer subtasks (and their threads)to avoid the event
        time skew related problems. To enable synchronization, set the watermark tracker
        on the consumer:
         */

        JobManagerWatermarkTracker watermarkTracker =
                new JobManagerWatermarkTracker(Constants.streamNames.get(stream));
        drumHitReadingFlinkKinesisConsumer.setWatermarkTracker(watermarkTracker);

        return env.addSource(drumHitReadingFlinkKinesisConsumer);
    }
}
