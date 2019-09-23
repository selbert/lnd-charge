package ch.puzzle.lightning.boundary;

import org.lightningj.lnd.wrapper.StatusException;
import org.lightningj.lnd.wrapper.ValidationException;

import javax.inject.Inject;
import javax.json.JsonObject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("info")
public class InfoBoundary {
    @Inject
    LndService lndService;

    @GET
    @Produces(APPLICATION_JSON)
    public JsonObject info() throws StatusException, ValidationException {
        return lndService.getInfo().toJson().build();
    }
}
