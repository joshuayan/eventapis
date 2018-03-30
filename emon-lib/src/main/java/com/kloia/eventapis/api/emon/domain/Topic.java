package com.kloia.eventapis.api.emon.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class Topic implements Serializable {

    private static final long serialVersionUID = -8668554375371818043L;

    private Map<String, ServiceData> serviceDataHashMap = new HashMap<>();

    private Long endOffSet = 0L;

    private List<Integer> partitions = Collections.singletonList(0);


    public void setPartitions(List<Integer> partitions) {
        this.partitions = partitions;
    }

    public Map<String, ServiceData> getServiceDataHashMap() {
        serviceDataHashMap.forEach((s, serviceData) -> serviceData.calculateLag(endOffSet));
        return serviceDataHashMap;
    }
}
