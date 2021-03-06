Cell : EnvironmentRedirect {

	classvar states;
	// An environment which holds settings and players
	// common to all cell instances
	classvar <>copyToProto, <playerTemplates, <players;
	classvar <>debug=false;

	//Cue name (for display purposes)
	var <>name;
	var modFunc, playerType;
	var <cond, playerCond;
	var <mother, <children;
	var <playAfterLoad;
	var <clock;
	var <>syncClock, <>syncQuant;
	var stateNum;

	*initClass {
		states = IdentityDictionary[
			\stopped -> 1,
			\loading -> 2,
			\ready -> 4,
			\playing -> 8,
			\paused -> 16,
			\stopping -> 32,
			\free -> 64,
			\waitForPlay -> 128,
			\error -> 256
		];

		copyToProto = #[settings, nodeMap, template, markers];
		playerTemplates = Environment();
		players = IdentityDictionary();

		StartUp.add({
			this.loadPlayerTemplates;
		});

	}

	*loadPlayerTemplates {

		playerTemplates.clear;
		players.clear;

		(PathName(this.filenameSymbol.asString).pathOnly +/+ "lib/synthDefs.scd").loadPaths;
		(PathName(this.filenameSymbol.asString).pathOnly +/+ "lib/playerTemplates.scd").loadPaths;

	}

	*addPlayer { |key, func, deps|
		playerTemplates.make {
			currentEnvironment[key] = CellTemplate(func, deps);
			players[key] = currentEnvironment[key].value;
		};
	}

	*removePlayer { |key|
		players[key] = nil;
	}


	*new { |func, playerKey, know=true|
		^super.new.init(func, playerKey, know);
	}

	init { |func, playerKey|

		cond = Condition(true);
		playerCond = Condition(true);
		children = IdentityDictionary();
		syncQuant = 0;
		syncClock = TempoClock.default;
		playAfterLoad = false;
		stateNum = states[\free];

		modFunc = func;
		playerType = playerKey;

		name = "";

		envir.know = true;

		envir.parent = players[playerKey];
		if (envir.parent.isNil) {
			if (playerKey.notNil) {
				"Cell player % not found".format(playerKey).warn;
			};
			envir.parent = players[\base];
		};

		// Copy some keys (eg settings, templates) to proto, to not overwrite the
		// class-level dictionary
		copyToProto.do { |key|
			envir.proto[key] = envir.parent[key].deepCopy;
		};

		// The make function is run inside the proto of the environment
		// that way, user data and temporary objects are kept separate from objects
		// created during init
		// EnvironmentRedirect.new have made the proto for us
		envir.proto.make(func);


		this.use {
			envir[\beforeInit].value(this);
			envir[\init].value(this);
			envir[\afterInit].value(this);
		};

	}

	//Run specific trigger(s) in envir, eg play, stop etc.
	//Should be run within a routine
	//Private
	trigAndWait { |...keys|

		keys.do { |key|
			playerCond.test = false;
			if (debug) {
				"into %".format(key).debug;
			};
			fork {
				protect {
					this.use {
						envir[key].value(this);
					};
				} { |err|
					if (err.notNil) {
						this.prChangeState(\error);
						playerCond.test = true;
						playerCond.signal;
					};
				};
				if (debug) {
					"out of %".format(key).debug;
				};
				playerCond.test = true;
				playerCond.signal;
			};
			playerCond.wait;
			// "truly out of %".format(key).debug;

		}

	}

	load { |ffwd|
		CmdPeriod.doOnce(this);
		cond.test = false;
		envir[\fastForward] = ffwd ? envir[\fastForward] ? 0;
		forkIfNeeded {
			if (this.checkState(\stopped, \error, \free)) {
				this.prChangeState(\loading);
				this.trigAndWait(\beforeLoad, \load, \afterLoad);

				if (envir[\fastForward].isNegative) {
					//TODO: This is a fallback,
					//Usually players take care of converting negative ffwd values,
					//but it's currently spread out in the player code. Find a
					//way to do this only once, but still adjust play starts according
					//to each player's needs
					var ffwd = envir.settings[\duration] + envir[\fastForward];
					if (ffwd == inf) { ffwd = 0 };
					envir[\fastForward] = ffwd;
				};
				// Play time in seconds (absolute)
				clock = TempoClock(envir[\settings][\tempo] / 60);
				if (this.checkState(\stopping, \error).not) {
					this.prChangeState(\ready);
					if (playAfterLoad) {
						playAfterLoad = false;
						this.play;
					};
				}
			};
			cond.test = true;
			cond.signal;
		};
	}

	play { |ffwd, argQuant, argClock|
		cond.test = false;
		argQuant !? { syncQuant = argQuant };
		argClock !? { syncClock = argClock };
		if (this.isReady and: { ffwd.notNil }) {
			this.free;
			this.play(ffwd);
		};
		forkIfNeeded {
			switch(stateNum,
				states[\stopped], { playAfterLoad = true;  this.load(ffwd) },
				states[\free], { playAfterLoad = true; this.load(ffwd) },
				states[\loading], { playAfterLoad = true },
				states[\ready], {
					//Set beats to sync 0 with syncClock's next beat according to syncQuant.
					//timeToNextBeat is in seconds, so multiply with this clock's tempo.

					clock.beats = (envir[\fastForward] -
						((syncClock.timeToNextBeat(syncQuant ? 0) ? 0) /
						syncClock.tempo)) * clock.tempo;

					this.prChangeState(\waitForPlay);

					clock.schedAbs(0, {
						fork {
							this.trigAndWait(\beforePlay, \play, \afterPlay);
							if (this.checkState(\stopping).not) {
								this.prChangeState(\playing);
							};
							cond.test = true;
							cond.signal;
						}
					});
				},
				states[\paused], { this.resume; cond.test = true; cond.signal }
			);

		};
	}

	stop { |now=false|
		cond.test = false;
		forkIfNeeded {

			if (this.checkState(\stopped, \stopping, \free).not) {
				this.prChangeState(\stopping);
				playerCond.wait; //If currently loading, wait until done before cleaning up
				this.trigAndWait(\beforeStop, \stop, \afterStop);
				this.afterStop;
				if (now) {
					this.freeAll;
				};

			};
			cond.test = true;
			cond.signal;
		};
	}

	afterStop {
		if (this.checkState(\stopped, \free).not) {
			this.prChangeState(\stopped);
		};
	}

	pause {
		this.notYetImplemented;
	}

	resume {
		this.notYetImplemented;
	}

	freeAll {
		if (this.checkState(\free).not) {
			this.use(envir[\freeAll]);
			this.use(envir[\afterFree]);
			clock = nil;
			this.prChangeState(\free);
		};
	}

	free {
		this.children.do { |child|
			child.free;
			child.unsetMother;
		};
		this.children.clear;
		if (this.checkState(\stopping, \stopped, \free, \error).not) {
			forkIfNeeded {
				this.stop(true);
				this.freeAll;
			}
		} {
			this.freeAll;
		};
	}

	//Wait for cond (in case we're currently triggering an action)
	//and then call function
	then { |func|
		forkIfNeeded {
			cond.wait;
			func.value(this);
		}
	}

	//Just wait, if needed
	wait {
		cond.wait;
	}

	//Current state as symbol
	state {
		^states.findKeyForValue(stateNum)
	}

	isStopped { ^this.checkState(\stopped) }
	isLoading { ^this.checkState(\loading) }
	isReady { ^this.checkState(\ready) }
	isPlaying { ^this.checkState(\playing) }
	isPaused { ^this.checkState(\paused) }

	prChangeState { |state|
		stateNum = states[state];
		this.changed(\state, state);
		this.changed(state);
	}

	//Check if state equals one of the supplied symbols
	checkState { |... sts|
		^(sts.collect(states[_]).reject(_.isNil).sum & stateNum) == stateNum;
	}

	cmdPeriod {
		// I'm leaving this here, as a safety thing
		// If error occurs, we at least would like to reset the state
		// There might be a possible race condition,
		// but it seems that in the normal case,
		// state == \free already
		if (this.checkState.(\stopped, \free).not) {
			this.freeAll;
		}
	}



	addChildren { |...pairs|
		if (pairs.size.odd, { Error("addChildren should have even number of args.\n").throw; });
		pairs.pairsDo { |key, child|
			if (this.validateRelative(child)) {
				children.put(key, child);
				child.setMother(this);
			} {
				Error("Not a valid child").throw;
			}
		};
	}

	removeChildren { |...keys|
		var child;
		keys.do { |key|
			child = children[key];
			children[key] = nil;
			if (child.notNil and: { child.mother == this }) {
				child.unsetMother;
			};
		};
	}

	// Set mother
	// if childKey is provided,
	// set this as children of mother
	// under key childKey
	setMother { |obj, childKey|
		if (this.validateRelative(obj)) {
			mother = obj;
			if (childKey.notNil) {
				obj.addChildren(childKey, this)
			}
		} {
			"Not a valid mother".warn;
		}
	}

	unsetMother {
		mother = nil;
	}

	//Chek if an object could be a valid child/mother
	validateRelative { |other|
		^other.isKindOf(this.class)
	}

	siblings {
		^mother !? {
			mother.children.reject(_==this);
		};
	}

	// Round seconds to closest quantized beat
	// Haven't found a way to do this on clock,
	// so this is mostly borrowed from TempoClock:nextTimeOnGrid
	roundToQuant { |seconds|
		var phase, quant = this.getQuant;
		phase = (quant.phase ? 0) - (quant.timingOffset ? 0);
		quant = quant.quant;


		if (quant == 0) { ^seconds + phase };
		if (quant < 0) { quant = clock.beatsPerBar * quant.neg };
		if (phase < 0) { phase = phase % quant };

		seconds = syncClock.secs2beats(seconds);

		^syncClock.beats2secs(
			roundUp(seconds - syncClock.baseBarBeat - (phase % quant), quant)
			+ syncClock.baseBarBeat + phase
		);
	}

	// Time (in seconds) to position, relative to this clock
	// Offset: An offset in seconds. Positive offset = later.
	// quantSync: if true, quantize time to closest beat as defined in ~settings[\quant].
	// Offset is added before quant, subject to change.
	timeToPos { |cue, offset=0, quantSync=false|
		^clock !? {
			var playPos,
			cueTime = case(
				{ cue == \playStart }, { 0 },
				{ cue == \playEnd }, { this.settings[\duration] },
				{ cue.isKindOf(Symbol) }, {
					cue = this.getMarkerTime(cue);
				},
				{ cue.isNumber }, { cue }
			);

			if (this.checkState(\playing, \waitForPlay)) {
				playPos = clock.seconds;
			} {
				playPos = clock.beats2secs((envir[\fastForward] ? 0) * clock.tempo);
			};

			cueTime !? {
				//Set cueTime to seconds relative to cue start
				//(ignore ffwd)
				cueTime = clock.beats2secs(0) + cueTime;


				//Sync with quant
				if (quantSync) {
					cueTime = this.roundToQuant(cueTime);
					//If cueTime is close, the rounding might make us
					//end up with a time in the past,
					//In that case, go with next beat instead
					if (cueTime < clock.seconds) {
						cueTime = this.clock.beats2secs(this.getQuant.nextTimeOnGrid(clock));
					};
				};
				cueTime - playPos + offset;
			};
		}
	}

	waitForPos { |cue, offset=0, quantSync=false|
		var time = this.timeToPos(cue, offset, quantSync);
		var tempo = thisThread.clock.tryPerform(\tempo) ? 1;
		if (time.notNil) {
			time = time * tempo;
			if (this.class.debug) {
				time.debug("Wait for position");
			};
			time.wait;
		};
		^time
	}

	doFunctionPerform { arg selector, args;
		envir[\forward] !? {
			if(envir[selector].isNil) {
				// This is different: We call function within envir
				^this.use {
					envir[\forward].functionPerformList(\value, selector, args)
				};
			}
		};
		// This is different: We call function within envir
		^this.use { this[selector].functionPerformList(\value, args) };
	}

	copy {
		^this.class.new(modFunc, playerType);
	}

	clone { |func, playerKey|

		if (func.isNil) {
			func = modFunc
		} {
			func = modFunc.addFunc(func);
		};

		if (playerKey.isNil) {
			playerKey = playerType
		};

		^this.class.new(func, playerType);
	}

	printOn { arg stream; stream << this.class.name << "(" <<< name <<")" }
}
