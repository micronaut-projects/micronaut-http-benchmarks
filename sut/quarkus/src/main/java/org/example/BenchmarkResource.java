package org.example;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.vertx.core.Vertx;
import io.vertx.core.impl.VertxInternal;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class BenchmarkResource {
    private final Vertx vertx;

    public BenchmarkResource(Vertx vertx) {
        this.vertx = vertx;
    }

    @GET
    @Path("status")
    public Status status() {
        return new Status(((VertxInternal) vertx).transport().getClass().getName());
    }

    @POST
    @Path("search/find")
    public Response find(SearchRequest request) {
        for (int listIndex = 0; listIndex < request.haystack().size(); listIndex++) {
            int stringIndex = request.haystack().get(listIndex).indexOf(request.needle());
            if (stringIndex >= 0) {
                return Response.ok(new SearchResult(listIndex, stringIndex)).build();
            }
        }
        return Response.status(Response.Status.NOT_FOUND).build();
    }

    @RegisterForReflection
    public record SearchRequest(List<String> haystack, String needle) {
    }

    @RegisterForReflection
    public record SearchResult(int listIndex, int stringIndex) {
    }

    @RegisterForReflection
    public record Status(String serverSocketChannelImplementation) {
    }
}
