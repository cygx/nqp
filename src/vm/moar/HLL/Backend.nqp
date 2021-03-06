# Backend class for the MoarVM.
class HLL::Backend::MoarVM {
    our %moar_config := nqp::backendconfig();

    method apply_transcodings($s, $transcode) {
        $s
    }

    method config() {
        %moar_config
    }

    method force_gc() {
        nqp::force_gc();
    }

    method name() {
        %moar_config<name>
    }

    method nqpevent($spec?) {
        # Doesn't do anything just yet
    }

    my $prof_start_sub;
    my $prof_end_sub;
    method ensure_prof_routines() {
        unless $prof_start_sub {
            $prof_start_sub := self.compunit_mainline(self.mbc(self.mast(QAST::CompUnit.new(
                QAST::Block.new(
                    QAST::Op.new( :op('mvmstartprofile'),
                        QAST::Var.new( :name('config'), :scope('local'), :decl('param') ) )
                )))));
            $prof_end_sub := self.compunit_mainline(self.mbc(self.mast(QAST::CompUnit.new(
                QAST::Block.new(
                    QAST::Op.new( :op('mvmendprofile') )
                )))));
        }
    }
    method run_profiled($what, $filename?) {
        my @END := nqp::gethllsym('perl6', '@END_PHASERS');
        @END.push: -> { self.dump_profile_data($prof_end_sub(), $filename) } if nqp::defined(@END);
        self.ensure_prof_routines();
        $prof_start_sub(nqp::hash());
        my $res  := $what();
        unless nqp::defined(@END) {
            my $data := $prof_end_sub();
            self.dump_profile_data($data, $filename);
        }
        $res;
    }
    method dump_profile_data($data, $filename) {
        my @pieces := nqp::list_s();

        unless nqp::defined($filename) {
            $filename := 'profile-' ~ nqp::time_n() ~ '.html';
        }
        nqp::sayfh(nqp::getstderr(), "Writing profiler output to $filename");
        my $profile_fh := open($filename, :w);
        my $want_json  := ?($filename ~~ /'.json'$/);

        my $escaped_backslash;
        my $escaped_dquote;
        my $escaped_squote;
        if $want_json {
            # Single quotes don't require escaping here
            $escaped_backslash := q{\\\\};
            $escaped_dquote    := q{\\"};
        }
        else {
            # Here we're creating a double-quoted JSON string destined for the
            # inside of a single-quoted JS string. Ouch.
            $escaped_backslash := q{\\\\\\\\};
            $escaped_dquote    := q{\\\\"};
            $escaped_squote    := q{\\'};
        }

        sub post_process_call_graph_node($node) {
            for $node<allocations> -> %alloc_info {
                my $type := %alloc_info<type>;
                %alloc_info<type> := $type.HOW.name($type);
            }
            if $node<callees> {
                for $node<callees> {
                    post_process_call_graph_node($_);
                }
            }
        }

        sub to_json($obj) {
            if nqp::islist($obj) {
                nqp::push_s(@pieces, '[');
                my $first := 1;
                for $obj {
                    if $first {
                        $first := 0;
                    }
                    else {
                        nqp::push_s(@pieces, ',');
                    }
                    to_json($_);
                }
                nqp::push_s(@pieces, ']');
            }
            elsif nqp::ishash($obj) {
                nqp::push_s(@pieces, '{');
                my $first := 1;
                for $obj {
                    if $first {
                        $first := 0;
                    }
                    else {
                        nqp::push_s(@pieces, ',');
                    }
                    nqp::push_s(@pieces, '"');
                    nqp::push_s(@pieces, $_.key);
                    nqp::push_s(@pieces, '":');
                    to_json($_.value);
                }
                nqp::push_s(@pieces, '}');
            }
            elsif nqp::isstr($obj) {
                if nqp::index($obj, '\\') {
                    $obj := subst($obj, /'\\'/, $escaped_backslash, :global);
                }
                if nqp::index($obj, '"') {
                    $obj := subst($obj, /'"'/, $escaped_dquote, :global);
                }
                if nqp::defined($escaped_squote) && nqp::index($obj, "'") {
                    $obj := subst($obj, /"'"/, $escaped_squote, :global);
                }
                nqp::push_s(@pieces, '"');
                nqp::push_s(@pieces, $obj);
                nqp::push_s(@pieces, '"');
            }
            elsif nqp::isint($obj) || nqp::isnum($obj) {
                nqp::push_s(@pieces, ~$obj);
            }
            elsif nqp::can($obj, 'Str') {
                to_json(nqp::unbox_s($obj.Str));
            }
            else {
                nqp::die("Don't know how to dump a " ~ $obj.HOW.name($obj));
            }
            if nqp::elems(@pieces) > 4096 {
                nqp::printfh($profile_fh, nqp::join('', @pieces));
                nqp::setelems(@pieces, 0);
            }
        }

        # Post-process the call data, turning objects into flat data.
        for $data {
            post_process_call_graph_node($_<call_graph>);
        }

        if $want_json {
            to_json($data);
            nqp::printfh($profile_fh, nqp::join('', @pieces));
        }
        else {
            # Get profiler template, split it in half, and write those either
            # side of the JSON itself.
            my $template := try slurp('src/vm/moar/profiler/template.html');
            unless $template {
                $template := slurp(nqp::backendconfig()<prefix> ~ '/share/nqp/lib/profiler/template.html');
            }
            my @tpl_pieces := nqp::split('{{{PROFIELR_OUTPUT}}}', $template);

            nqp::printfh($profile_fh, @tpl_pieces[0]);
            to_json($data);
            nqp::printfh($profile_fh, nqp::join('', @pieces));
            nqp::printfh($profile_fh, @tpl_pieces[1]);
        }
        nqp::closefh($profile_fh);
    }

    method run_traced($level, $what) {
        nqp::die("No tracing support");
    }

    method version_string() {
        my $rev := %moar_config<version> // '(unknown)';
        return "MoarVM version $rev";
    }

    method stages() {
        'mast mbc moar'
    }

    method is_precomp_stage($stage) {
        $stage eq 'mbc'
    }

    method is_textual_stage($stage) {
        0
    }

    method mast($qast, *%adverbs) {
        nqp::getcomp('QAST').to_mast($qast);
    }

    method mbc($mast, *%adverbs) {
        my $assmblr := nqp::getcomp('MAST');
        if %adverbs<target> eq 'mbc' && %adverbs<output> {
            $assmblr.assemble_to_file($mast, %adverbs<output>);
            nqp::null()
        }
        else {
            my $boot_mode := %adverbs<bootstrap> ?? 1 !! 0;
            __MVM__usecompileehllconfig() if $boot_mode;
            my $result := $assmblr.assemble_and_load($mast);
            __MVM__usecompilerhllconfig() if $boot_mode;
            $result
        }
    }

    method moar($cu, *%adverbs) {
        $cu
    }

    method is_compunit($cuish) {
        __MVM__iscompunit($cuish)
    }

    method compunit_mainline($cu) {
        __MVM__compunitmainline($cu)
    }

    method compunit_coderefs($cu) {
        __MVM__compunitcodes($cu)
    }
}

# Role specifying the default backend for this build.
role HLL::Backend::Default {
    method default_backend() { HLL::Backend::MoarVM }
}
