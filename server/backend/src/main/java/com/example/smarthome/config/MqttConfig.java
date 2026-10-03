package com.example.smarthome.config;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

/**
 * Kết nối MQTT tới broker SERVER (mục 9):
 *  - Inbound  : subscribe sh/+/register, sh/+/telemetry, sh/+/status, sh/+/ack (QoS 1)
 *  - Outbound : publish  sh/{id}/cmd (QoS 1)
 *  - TLS      : nạp ca.crt của đồ án vào TrustManager (broker dùng cert self-signed)
 */
@Configuration
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MqttConfig {

    /** Kênh Spring Integration: adapter nhận message -> xử lý nội bộ. */
    public static final String INBOUND_CHANNEL = "mqttInChannel";

    /** Kênh Spring Integration: gửi message ra -> MqttPahoMessageHandler. */
    public static final String OUTBOUND_CHANNEL = "mqttOutChannel";

    /** Bốn topic thiết bị gửi lên backend (mục 2 - bảng topic). */
    private static final String[] INBOUND_TOPICS = {
            "sh/+/register", "sh/+/telemetry", "sh/+/status", "sh/+/ack"
    };

    private static final Logger log = LoggerFactory.getLogger(MqttConfig.class);

    private final MqttProperties props;

    public MqttConfig(MqttProperties props) {
        this.props = props;
    }

    /** Paho client factory: TLS + tài khoản backend + tự nối lại khi mất broker. */
    @Bean
    public MqttPahoClientFactory mqttClientFactory() {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();

        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(props.getUrl().split(","));
        options.setUserName(props.getUsername());
        options.setPassword(props.getPassword().toCharArray());
        // cleansession=false + clientId cố định: broker giữ message QoS1 khi backend tắt
        options.setCleanSession(false);
        // automaticReconnect: khi broker chưa lên hoặc mất kết nối, Paho tự thử lại (SI 6.x
        // không còn setRecoveryInterval trên adapter) -> backend không sập vì thiếu broker.
        options.setAutomaticReconnect(true);
        options.setMaxReconnectDelay((int) props.getRecoveryIntervalMs());
        options.setConnectionTimeout(props.getConnectionTimeoutSeconds());
        options.setKeepAliveInterval(props.getKeepAliveSeconds());
        if (props.getUrl().startsWith("ssl://") || props.getUrl().startsWith("wss://")) {
            options.setSocketFactory(sslSocketFactory());
        } else {
            log.warn("MQTT: URL '{}' không dùng TLS - chỉ dùng khi thử nghiệm nội bộ", props.getUrl());
        }
        factory.setConnectionOptions(options);

        log.info("MQTT: cấu hình tới {} (user={}, clientId={})",
                props.getUrl(), props.getUsername(), props.getClientId());
        return factory;
    }

    @Bean(name = INBOUND_CHANNEL)
    public MessageChannel mqttInChannel() {
        return new DirectChannel();
    }

    @Bean(name = OUTBOUND_CHANNEL)
    public MessageChannel mqttOutChannel() {
        return new DirectChannel();
    }

    /** Nhận dữ liệu thiết bị từ broker SERVER (qua bridge từ broker LOCAL). */
    @Bean
    public MqttPahoMessageDrivenChannelAdapter mqttInbound(MqttPahoClientFactory mqttClientFactory) {
        MqttPahoMessageDrivenChannelAdapter adapter = new MqttPahoMessageDrivenChannelAdapter(
                props.getClientId() + "-sub", mqttClientFactory, INBOUND_TOPICS);
        adapter.setQos(props.getQos());
        adapter.setCompletionTimeout(5000);
        // Broker chưa chạy lúc backend khởi động: Mqttv3ClientManager + automaticReconnect
        // sẽ thử lại định kỳ thay vì làm chết context.
        adapter.setOutputChannel(mqttInChannel());
        return adapter;
    }

    /** Gửi lệnh điều khiển xuống thiết bị (sh/{id}/cmd, QoS 1). */
    @Bean
    @ServiceActivator(inputChannel = OUTBOUND_CHANNEL)
    public MessageHandler mqttOutbound(MqttPahoClientFactory mqttClientFactory) {
        MqttPahoMessageHandler handler = new MqttPahoMessageHandler(
                props.getClientId() + "-pub", mqttClientFactory);
        handler.setAsync(true);
        handler.setDefaultQos(props.getQos());
        handler.setDefaultRetained(false);
        handler.setCompletionTimeout(5000);
        return handler;
    }

    /** SSLContext dùng đúng ca.crt của đồ án (không dùng truststore mặc định của JVM). */
    private SSLSocketFactory sslSocketFactory() {
        Path caPath = Path.of(props.getCaCert());
        if (!Files.isReadable(caPath)) {
            throw new IllegalStateException("Không đọc được file CA của MQTT: " + caPath.toAbsolutePath()
                    + " - hãy đặt biến môi trường MQTT_CA_CERT trỏ tới certs/ca.crt");
        }
        try (InputStream in = Files.newInputStream(caPath)) {
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            X509Certificate caCertificate = (X509Certificate) certificateFactory.generateCertificate(in);

            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            keyStore.setCertificateEntry("mqtt-ca", caCertificate);

            TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(keyStore);

            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(null, trustManagerFactory.getTrustManagers(), null);

            log.info("MQTT: đã nạp CA {} (subject: {})",
                    caPath.toAbsolutePath(), caCertificate.getSubjectX500Principal().getName());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("Không tạo được SSLContext từ " + caPath.toAbsolutePath(), e);
        }
    }

}
