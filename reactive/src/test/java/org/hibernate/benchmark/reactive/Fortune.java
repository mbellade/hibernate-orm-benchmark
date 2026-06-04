package org.hibernate.benchmark.reactive;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity(name = "Fortune")
@Table(name = "fortune")
public class Fortune {

	@Id
	public Integer id;

	@Column(length = 512)
	public String message;

	public Fortune() {
	}

	public Fortune(Integer id, String message) {
		this.id = id;
		this.message = message;
	}
}
