package org.constellation.primitives.storage

import org.constellation.primitives.Schema.SignedObservationEdgeCache

class SOEService(expireAfterMinutes: Int) extends StorageService[SignedObservationEdgeCache](expireAfterMinutes = Some(expireAfterMinutes))
