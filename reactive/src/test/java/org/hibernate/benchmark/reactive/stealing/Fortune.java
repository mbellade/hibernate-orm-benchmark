package org.hibernate.benchmark.reactive.stealing;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class Fortune {

	@Id
	private Integer id;

	private String message;

	public Fortune() {
	}

	public Fortune(Integer id, String message) {
		this.id = id;
		this.message = message;
	}
}
