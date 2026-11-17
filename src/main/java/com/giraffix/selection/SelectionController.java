package com.giraffix.selection;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Validator;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.net.URI;
import java.util.UUID;
import static com.giraffix.selection.SelectionModels.*;

@RestController
@RequestMapping("/api/v1/selection-requests")
public class SelectionController {
    private final SelectionService service;
    private final JsonSupport json;
    private final Validator validator;
    public SelectionController(SelectionService service,JsonSupport json,Validator validator){this.service=service;this.json=json;this.validator=validator;}
    @PostMapping
    @Operation(summary="Select an advertising concept for a product",
            description="Only productId is required. Loads the saved product, photos and brand; omitted intent and format use defaults. Returns HTTP 202; poll the Location URL for the result.",
            requestBody=@io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,
                    content=@Content(mediaType="application/json",schema=@Schema(implementation=Start.class),
                            examples=@ExampleObject(name="automaticSelection",summary="Pick a concept automatically",
                                    value="""
                                        {
                                          "productId": "00000000-0000-0000-0000-000000000001"
                                        }
                                        """))))
    public ResponseEntity<Status> start(@AuthenticationPrincipal OidcUser user,
            @Parameter(description="Required unique operation key. Reuse the same key and body to retrieve the same request.",
                    example="picker-test-001",required=true)
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @Parameter(hidden=true) @RequestBody tools.jackson.databind.JsonNode body){
        Start input;
        try {
            if(!body.isObject())throw new IllegalArgumentException();
            for(String name:body.propertyNames())if(!java.util.List.of("productId","intent","format","controls","conceptId","aspectRatio","quality").contains(name))throw new IllegalArgumentException();
            input=json.mapper.treeToValue(body,Start.class);
            if(!validator.validate(input).isEmpty())throw new IllegalArgumentException();
        }catch(RuntimeException ex){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"Invalid selection request");}
        var result=service.start(user.getSubject(),key,input);
        return ResponseEntity.accepted().location(URI.create("/api/v1/selection-requests/"+result.id())).body(result);
    }
    @GetMapping("/{id}")
    public Status get(@AuthenticationPrincipal OidcUser user,@PathVariable UUID id){return service.status(user.getSubject(),id);}
}
