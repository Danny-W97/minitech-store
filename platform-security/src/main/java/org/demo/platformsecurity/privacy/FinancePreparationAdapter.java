package org.demo.platformsecurity.privacy;

import java.util.*;

public interface FinancePreparationAdapter {
    record Result(String state,String code,String cursor){
        public Result{
            if(!Set.of("PREPARED","MORE","WAIT").contains(state)||code==null||!code.matches("[A-Z0-9_]{1,60}")||cursor==null||!cursor.matches("[0-9]{1,20}-[0-9]{1,20}"))throw new IllegalArgumentException("Invalid finance preparation result");
        }
        public static Result prepared(){return new Result("PREPARED","PREPARED","0-0");}
        public static Result waitFor(String code,String cursor){return new Result("WAIT",code,cursor);}
        public static Result more(String cursor){return new Result("MORE","MORE_ROWS",cursor);}
    }
    Result prepare(long customer,UUID request,long order,long version,String cursor);
    static CommerceOrderSource.Entry exact(CommerceOrderSource source,long customer,UUID request,long order,long version){
        var row=source.next(customer,request,order-1).orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Owned order scope changed"));
        if(row.orderId()!=order||row.orderVersion()!=version)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Order revision changed");return row;
    }
}
