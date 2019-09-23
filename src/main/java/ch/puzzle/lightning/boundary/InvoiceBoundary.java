package ch.puzzle.lightning.boundary;

import org.lightningj.lnd.wrapper.StatusException;
import org.lightningj.lnd.wrapper.ValidationException;

import javax.inject.Inject;
import javax.json.JsonObject;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.sse.SseEventSink;

@Path("invoice")
public class InvoiceBoundary {

    @Inject
    LndService lndService;

    @POST
    @Consumes("application/json")
    public JsonObject invoice(GuiInvoiceRequest invoiceRequest) throws StatusException, ValidationException {
        return lndService.addInvoice(invoiceRequest.amount, invoiceRequest.memo).toJson().build();
    }

    @GET
    @Path("{id}")
    public JsonObject invoiceStatus(@PathParam("id") String rHash) throws StatusException, ValidationException {
        return lndService.getInvoice(rHash).toJson().build();
    }

    public static class GuiInvoiceRequest {
        public Long amount;
        public String memo;
    }


    @GET
    @Path("sse/{reg}")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void consume(@PathParam("reg") String reg,@Context SseEventSink sseEventSink) {
        lndService.register(reg, sseEventSink);
    }
}
