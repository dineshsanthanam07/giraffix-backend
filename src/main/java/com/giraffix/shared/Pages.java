package com.giraffix.shared;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class Pages {
    private Pages(){}
    public static PageRequest of(int page,int size){
        if(page<0 || page>1000000 || size<1 || size>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use page >= 0 and size between 1 and 100");
        return PageRequest.of(page,size);
    }
}
