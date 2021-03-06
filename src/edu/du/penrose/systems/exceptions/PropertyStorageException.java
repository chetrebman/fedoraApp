/*
 * Copyright 2012 University of Denver
 * Author Chet Rebman
 * 
 * This file is part of FedoraApp.
 * 
 * FedoraApp is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * FedoraApp is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with FedoraApp.  If not, see <http://www.gnu.org/licenses/>.
*/

package edu.du.penrose.systems.exceptions;

/**
 * This checked exception is thrown when unable to save a program property to 
 * the underlying storage medium.
 * 
 * @author chet.rebman
 *
 */
public class PropertyStorageException  extends Exception {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	public PropertyStorageException() {
		super ( "Unable to save to Program Properties storage medium" );
	}

	public PropertyStorageException(String message) {
		super(message);
	}

	public PropertyStorageException(Throwable cause) {
		super(cause);
	}

	public PropertyStorageException(String message, Throwable cause) {
		super(message, cause);
	}

} // PropertyStorageError
