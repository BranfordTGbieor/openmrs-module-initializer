package org.openmrs.module.initializer.api.obs;

import org.apache.commons.lang3.StringUtils;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.openmrs.Concept;
import org.openmrs.ConceptDatatype;
import org.openmrs.Encounter;
import org.openmrs.Location;
import org.openmrs.Obs;
import org.openmrs.Person;
import org.openmrs.api.ConceptService;
import org.openmrs.api.EncounterService;
import org.openmrs.api.LocationService;
import org.openmrs.api.ObsService;
import org.openmrs.api.PersonService;
import org.openmrs.api.context.Context;
import org.openmrs.module.initializer.api.BaseLineProcessor;
import org.openmrs.module.initializer.api.CsvLine;

import java.util.Date;

public class ObsLineProcessor extends BaseLineProcessor<Obs, ObsService> {
	
	public static final String HEADER_DATE = "Date";
	
	public static final String HEADER_PERSON_UUID = "Person UUID";
	
	public static final String HEADER_LOCATION = "Location";
	
	public static final String HEADER_ENCOUNTER_UUID = "Encounter UUID";
	
	public static final String HEADER_CONCEPT_REFERENCE_TERM = "Concept Reference Term";
	
	public static final String HEADER_CONCEPT_NAME = "Concept Name";
	
	public static final String HEADER_VALUE = "Value";
	
	public ObsLineProcessor(String[] headerLine, ObsService es) {
		super(headerLine, es);
	}
	
	@Override
	protected Obs bootstrap(CsvLine line) throws IllegalArgumentException {
		String uuid = getUuid(line.asLine());
		Obs enc = service.getObsByUuid(uuid);
		
		if (enc == null) {
			enc = new Obs();
			if (!StringUtils.isEmpty(uuid)) {
				enc.setUuid(uuid);
			}
		}
		
		enc.setVoided(getVoidOrRetire(line.asLine()));
		
		return enc;
	}
	
	@Override
	protected Obs fill(Obs obs, CsvLine line) throws IllegalArgumentException {
		EncounterService es = Context.getEncounterService();
		String encounterUuid = line.get(HEADER_ENCOUNTER_UUID);
		Encounter enc = null;
		if (encounterUuid != null && !encounterUuid.isEmpty()) {
			enc = es.getEncounterByUuid(encounterUuid);
			if (enc == null) {
				throw new IllegalArgumentException("Invalid " + HEADER_ENCOUNTER_UUID);
			} else {
				obs.setEncounter(enc);
			}
		}
		
		Date date = getAndParseDate(line);
		if (date == null) {
			date = dateFromEncounter(enc);
		}
		if (date == null) {
			throwBadHeader(HEADER_DATE);
		}
		obs.setObsDatetime(date);
		
		Person person = getPerson(line);
		if (person == null) {
			person = personFromEncounter(enc);
		}
		if (person == null) {
			throwBadHeader(HEADER_PERSON_UUID);
		}
		obs.setPerson(person);
		
		Location loc = getLocation(line);
		if (loc == null) {
			loc = locationFromEncounter(enc);
		}
		if (loc == null) {
			throwBadHeader(HEADER_LOCATION);
		}
		obs.setLocation(loc);
		
		String conceptRefTerm = line.get(HEADER_CONCEPT_REFERENCE_TERM);
		String conceptName = line.get(HEADER_CONCEPT_NAME);
		ConceptService cs = Context.getConceptService();
		Concept c;
		if (conceptName != null) {
			c = cs.getConceptByName(conceptName);
			if (c == null) {
				throw new IllegalArgumentException("Invalid " + HEADER_CONCEPT_NAME);
			}
		} else if (conceptRefTerm != null) {
			String[] termSourceAndTerm = conceptRefTerm.split(":");
			if (termSourceAndTerm.length != 2) {
				throw new IllegalArgumentException("Concept Reference Terms should be specified like 'Term Source:Code'");
			} else {
				c = cs.getConceptByMapping(termSourceAndTerm[1], termSourceAndTerm[0]);
				if (c == null) {
					throw new IllegalArgumentException("Invalid " + HEADER_CONCEPT_REFERENCE_TERM);
				}
			}
		} else {
			throw new IllegalArgumentException(String.format("Concept must be specified, either by %s or %s",
			    HEADER_CONCEPT_NAME, HEADER_CONCEPT_REFERENCE_TERM));
		}
		obs.setConcept(c);
		
		ConceptDatatype datatype = c.getDatatype();
		if (datatype.isBoolean()) {
			Boolean value = line.getBool(HEADER_VALUE);
			obs.setValueBoolean(value);
		} else if (datatype.isCoded()) {
			String value = line.get(HEADER_VALUE);
			Concept answer = cs.getConceptByName(value);
			obs.setValueCoded(answer);
		} else if (datatype.isDate() || datatype.isDateTime() || datatype.isTime()) {
			String valueString = line.get(HEADER_VALUE);
			DateTimeFormatter parser = ISODateTimeFormat.dateTimeNoMillis();
			Date value = parser.parseDateTime(valueString).toDate();
			obs.setValueDatetime(value);
		} else if (datatype.isNumeric()) {
			Double value = line.getDouble(HEADER_VALUE);
			obs.setValueNumeric(value);
		} else if (datatype.isText()) {
			String value = line.get(HEADER_VALUE);
			obs.setValueText(value);
		} // do nothing if datatype.isAnswerOnly()
		
		return obs;
	}
	
	private void throwBadHeader(String headerName) throws IllegalArgumentException {
		throw new IllegalArgumentException("Either " + headerName + " or " + HEADER_ENCOUNTER_UUID + " are required.");
	}
	
	private Date getAndParseDate(CsvLine line) throws IllegalArgumentException {
		DateTimeFormatter parser = ISODateTimeFormat.dateTimeNoMillis();
		String dateString = line.get(HEADER_DATE);
		if (dateString != null && !dateString.isEmpty()) {
			return parser.parseDateTime(dateString).toDate();
		}
		return null;
	}
	
	private Date dateFromEncounter(Encounter enc) {
		if (enc != null) {
			return enc.getEncounterDatetime();
		}
		return null;
	}
	
	private Person getPerson(CsvLine line) throws IllegalArgumentException {
		String personUuidString = line.get(HEADER_PERSON_UUID);
		if (personUuidString != null && !personUuidString.isEmpty()) {
			PersonService ps = Context.getPersonService();
			Person person = ps.getPersonByUuid(personUuidString);
			if (person == null) {
				throw new IllegalArgumentException("Invalid patient from " + HEADER_PERSON_UUID);
			}
			return person;
		} else {
			return null;
		}
	}
	
	private Person personFromEncounter(Encounter enc) {
		if (enc != null) {
			return enc.getPatient();
		}
		return null;
	}
	
	private Location getLocation(CsvLine line) {
		String locString = line.get(HEADER_LOCATION);
		if (locString != null && !locString.isEmpty()) {
			LocationService ls = Context.getLocationService();
			Location loc = ls.getLocation(locString);
			if (loc == null) {
				throw new IllegalArgumentException("Invalid location from " + HEADER_LOCATION);
			}
			return loc;
		}
		return null;
	}
	
	private Location locationFromEncounter(Encounter enc) {
		if (enc != null) {
			return enc.getLocation();
		}
		return null;
	}
	
}
