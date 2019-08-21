knowhow ModuleLoader {
    my %modules_loaded;
    my %settings_loaded;
    my %cache := nqp::getcurhllsym('bytecode_cache');

    method !load(%loaded, $path, @search_paths) {
        my $buffer;

        # Check the bytecode cache first.
        if nqp::defined(%cache) && nqp::existskey(%cache, $path) {
            $buffer := nqp::atkey(%cache, $path);
        }

        # Otherwise, hit the file system.
        else {
            for @search_paths -> $prefix {
                if nqp::stat("$prefix/$path", 0) {
                    $path := "$prefix/$path";
                    last;
                }
            }
        }

        # XXX It would be nice to check %loaded before hitting the file system,
        # but this breaks stage2 compilation...
        if nqp::existskey(%loaded, $path) {
            %loaded{$path};
        }
        else {
            my $*CTXSAVE := self;
            my $*MAIN_CTX := ModuleLoader;
            my $boot_mode;

            try { $boot_mode := nqp::ifnull(nqp::ifnull(%*COMPILING, {})<%?OPTIONS>, {})<bootstrap>; }
            $boot_mode := !nqp::isnull($boot_mode) && $boot_mode;

            my $preserve_global := nqp::getcurhllsym('GLOBAL');
            nqp::usecompileehllconfig() if $boot_mode;

            if nqp::defined($buffer) {
                nqp::loadbytecodebuffer($buffer);
            }
            else {
                nqp::loadbytecode($path);
            }

            nqp::usecompilerhllconfig() if $boot_mode;
            nqp::bindcurhllsym('GLOBAL', $preserve_global);

            %loaded{$path} := $*MAIN_CTX;
        }
    }

    method search_path($explicit_path) {
        my @search_paths;

        # Put any explicitly specified path on the start of the list.
        my $explicit;
        try { $explicit := nqp::ifnull(nqp::ifnull(%*COMPILING, {})<%?OPTIONS>, {}){$explicit_path}; }
        if !nqp::isnull($explicit) && nqp::defined($explicit) {
            nqp::push(@search_paths, $explicit);
        }
        my %env := nqp::getenvhash();
        if nqp::existskey(%env, 'NQP_LIB') {
            nqp::push(@search_paths, %env<NQP_LIB>);
        }

        @search_paths
    }

    method ctxsave() {
        $*MAIN_CTX := nqp::ctxcaller(nqp::ctx());
        $*CTXSAVE := 0;
    }

    method load_module($module_name, *@global_merge_target) {
        # If we didn't already do so, load the module and capture
        # its mainline. Otherwise, we already loaded it so go on
        # with what we already have.
        my $path := nqp::join('/', nqp::split('::', $module_name)) ~ '.moarvm';
        my @search_paths := self.search_path('module-path');
        my $module_ctx := self.'!load'(%modules_loaded, $path, @search_paths);

        # Provided we have a mainline...
        if nqp::defined($module_ctx) {
            # Merge any globals.
            my $UNIT := nqp::ctxlexpad($module_ctx);
            unless nqp::isnull($UNIT<GLOBALish>) {
                if nqp::elems(@global_merge_target) {
                    merge_globals(@global_merge_target[0], $UNIT<GLOBALish>);
                }
            }
        }

        $module_ctx;
    }

    # XXX This is a really dumb and minimalistic GLOBAL merger.
    # For a much more complete one, see sorear++'s work in
    # Niecza. This one will likely evolve towards that, but for
    # now I just need something that's just good enough for the
    # immediate needs of NQP bootstrapping.
    my $stub_how := 'KnowHOW';
    sub merge_globals($target, $source) {
        # XXX For now just merge the top level symbols;
        # if there's a conflict then don't dig any deeper.
        # Obviously, just a first cut at this. :-)
        my %known_symbols;
        for $target.WHO {
            %known_symbols{nqp::iterkey_s($_)} := 1;
        }
        for $source.WHO {
            my $sym := nqp::iterkey_s($_);
            my $val := nqp::iterval($_);
            if !nqp::existskey(%known_symbols, $sym) {
                my $source_is_stub := 0;
                # XXX TODO: Exceptions.
                #try {
                    my $source_mo := $val.HOW;
                    $source_is_stub := $source_mo.WHAT.HOW.name($source_mo) eq $stub_how &&
                        !nqp::isnull(nqp::who($val)) && nqp::who($val);
                #}
                if $source_is_stub {
                    my $source := $val;
                    my $source_clone := $source.HOW.new_type(:name($source.HOW.name($source)));
                    $source_clone.HOW.compose($source_clone);
                    my %WHO_clone;
                    for nqp::who($source) {
                        %WHO_clone{nqp::iterkey_s($_)} := nqp::iterval($_);
                    }
                    nqp::setwho($source_clone, %WHO_clone);
                    ($target.WHO){$sym} := $source_clone;
                }
                else {
                    ($target.WHO){$sym} := $val;
                }
            }
            elsif ($target.WHO){$sym} =:= $val {
                # No problemo; a symbol can't conflict with itself.
            }
            else {
                my $source_mo := $val.HOW;
                my $source_is_stub := $source_mo.WHAT.HOW.name($source_mo) eq $stub_how;
                my $target_mo := ($target.WHO){$sym}.HOW;
                my $target_is_stub := $target_mo.WHAT.HOW.name($target_mo) eq $stub_how;
                if $source_is_stub && $target_is_stub {
                    # Leave target as is, and merge the nested symbols.
                    merge_globals(($target.WHO){$sym}, $val);
                }
                else {
                    nqp::die("Merging GLOBAL symbols failed: duplicate definition of symbol $sym");
                }
            }
        }
    }

    method load_setting($setting_name) {
        my $setting;

        if $setting_name ne 'NULL' {
            # Add .setting suffix.
            my $path := "$setting_name.setting.moarvm";
            my @search_paths := self.search_path('setting-path');
            $setting := self.'!load'(%settings_loaded, $path, @search_paths);

            unless nqp::defined($setting) {
                nqp::die("Unable to load setting $setting_name; maybe it is missing a YOU_ARE_HERE?");
            }
        }

        $setting;
    }
}

# Since this *is* the module loader, we can't locate it the normal way by
# GLOBAL merging. So instead we stash it away in the current HLL directly.
nqp::bindcurhllsym('ModuleLoader', ModuleLoader);
