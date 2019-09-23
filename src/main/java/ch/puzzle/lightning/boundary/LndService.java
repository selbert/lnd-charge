package ch.puzzle.lightning.boundary;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider;
import io.grpc.stub.StreamObserver;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.lightningj.lnd.wrapper.*;
import org.lightningj.lnd.wrapper.message.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.sse.OutboundSseEvent;
import javax.ws.rs.sse.Sse;
import javax.ws.rs.sse.SseEventSink;
import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@ApplicationScoped
public class LndService {
    private static final Logger LOGGER = LoggerFactory.getLogger(LndService.class);

    @ConfigProperty(name = "INVOICE_MACAROON_HEX")
    String invoiceMacaroonHex;
    @ConfigProperty(name = "READONLY_MACAROON_HEX")
    String readonlyMacaroonHex;
    @ConfigProperty(name = "CERT_PATH")
    String certPath;
    @ConfigProperty(name = "LND_HOST")
    String lndHost;
    @ConfigProperty(name = "LND_RPC_PORT")
    int lndRpcPort;

    private static final String USE_CORS = "USE_CORS";
    private static final long SUBSCRIPTION_RETRY_INTERVAL = 5000L;
    private static final Map<String, SseEventSink> invoiceToSessionMap = new ConcurrentHashMap<>();

    private AsynchronousLndAPI asyncLndAPIIN;
    private SynchronousLndAPI syncLndAPIRO;
    private SynchronousLndAPI syncLndAPIIN;
    private OutboundSseEvent.Builder eventBuilder;

    @PostConstruct
    public void init() throws IOException, StatusException, ValidationException {
        SslContext sslContext = GrpcSslContexts
                .configure(SslContextBuilder.forClient(), SslProvider.OPENSSL)
                .trustManager(new File(certPath)).build();


        syncLndAPIRO = new SynchronousLndAPI(
                lndHost, lndRpcPort, sslContext, () -> readonlyMacaroonHex);
        asyncLndAPIIN = new AsynchronousLndAPI(
                lndHost, lndRpcPort, sslContext, () -> invoiceMacaroonHex);
        syncLndAPIIN = new SynchronousLndAPI(
                lndHost, lndRpcPort, sslContext, () -> invoiceMacaroonHex);

        startInvoiceSubscription();
    }


    @Context
    public void setSse(Sse sse) {
        this.eventBuilder = sse.newEventBuilder();
    }

    private final Consumer<Invoice> ON_NEXT = invoice -> {
        LOGGER.info("Invoice received from subscription: " + invoice.toJsonAsString(false));
        String reg = Base64.getEncoder().encodeToString(invoice.getRHash());
        SseEventSink user = invoiceToSessionMap.get(reg);
        if (user == null) LOGGER.info("No user found for invoice: " + reg);
        OutboundSseEvent sseEvent = eventBuilder
                .name("message")
                .id(reg)
                .mediaType(MediaType.APPLICATION_JSON_TYPE)
                .data(invoice.toJsonAsString(false))
                .reconnectDelay(3000)
                .build();

        if (user != null) {
            LOGGER.info("Sending invoice " + reg + " to " + user);
            user.send(sseEvent);
        }

    };

    public GetInfoResponse getInfo() throws StatusException, ValidationException {
        LOGGER.info("Get info request");
        return syncLndAPIRO.getInfo();
    }

    public AddInvoiceResponse addInvoice(Long amount, String memo) throws StatusException, ValidationException {
        LOGGER.info("New invoice for " + amount + " Satoshis");
        Invoice invoice = new Invoice();
        invoice.setValue(amount);
        invoice.setMemo(memo);

        // fix NullPointerException
        invoice.getRouteHints();
        return syncLndAPIIN.addInvoice(invoice);
    }

    public Invoice getInvoice(String invoiceHash) throws StatusException, ValidationException {
        LOGGER.info("Getting invoice " + invoiceHash);
        PaymentHash paymentHash = new PaymentHash();
        byte[] rhash = Base64.getDecoder().decode(invoiceHash);
        paymentHash.setRHash(rhash);
        return syncLndAPIIN.lookupInvoice(paymentHash);
    }

    private void startInvoiceSubscription() throws StatusException, ValidationException {
        LOGGER.info("Starting invoice subscription");
        asyncLndAPIIN.subscribeInvoices(new InvoiceSubscription(), new StreamObserver<Invoice>() {
            @Override
            public void onNext(Invoice invoice) {
                ON_NEXT.accept(invoice);
            }

            @Override
            public void onError(Throwable throwable) {
                LOGGER.error("Error while subscribed to invoices", throwable);
                try {
                    Thread.sleep(SUBSCRIPTION_RETRY_INTERVAL);
                    startInvoiceSubscription();
                } catch (InterruptedException | ValidationException | StatusException e) {
                    LOGGER.error("Error while waiting to retry for subscription", e);
                }
            }

            @Override
            public void onCompleted() {
                LOGGER.info("Subscription complete");
            }
        });
    }

    void register(String reg, SseEventSink user) {
        invoiceToSessionMap.put(reg, user);
    }
}
