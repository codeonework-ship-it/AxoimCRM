package com.axiom.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/pipeline")
public class PipelineController {

    private final QueryService queries;

    public PipelineController(QueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/board")
    public List<QueryService.BoardStage> board() {
        return queries.pipelineBoard();
    }
}
