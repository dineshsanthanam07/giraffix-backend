package com.giraffix.generation;

import com.giraffix.selection.SelectionStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

/** Shared authorized snapshot for polling and live progress; never invokes providers. */
@Service
public class AdStatusService {
    private final SelectionStore store;
    public AdStatusService(SelectionStore store){this.store=store;}

    public AdController.Status get(String subject,UUID id){
        return store.db.query("""
                SELECT s.status AS selection_status,s.context_json,s.fallback_used,
                       g.id AS job_id,g.status AS job_status,g.failure_code,g.failure_stage,g.failure_message,c.id AS creation_id
                FROM selection_requests s
                JOIN users u ON u.id=s.owner_id
                LEFT JOIN concept_allocations a ON a.request_id=s.id
                LEFT JOIN generation_jobs g ON g.allocation_id=a.id
                LEFT JOIN creations c ON c.job_id=g.id AND c.owner_id=u.id
                WHERE s.id=? AND u.google_id=?
                """,(r,n)->{
            var context=store.json.mapper.readTree(r.getString("context_json"));
            UUID creation=r.getObject("creation_id",UUID.class);
            String selection=r.getString("selection_status"),generation=r.getString("job_status");
            return new AdController.Status(id,generation==null?selection:generation,selection,r.getObject("job_id",UUID.class),creation,
                    creation==null?null:"/api/v1/creations/"+creation+"/image",context.path("aspectRatio").asText(null),
                    context.path("quality").asText("high"),r.getBoolean("fallback_used"),
                    r.getString("failure_code")==null?null:new AdController.Error(r.getString("failure_code"),r.getString("failure_stage"),r.getString("failure_message")));
        },id,subject).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Ad not found"));
    }
}
